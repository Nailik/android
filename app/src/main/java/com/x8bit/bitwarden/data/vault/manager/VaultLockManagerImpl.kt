package com.x8bit.bitwarden.data.vault.manager

import android.os.SystemClock
import com.bitwarden.core.InitOrgCryptoRequest
import com.bitwarden.core.InitUserCryptoMethod
import com.bitwarden.core.InitUserCryptoRequest
import com.bitwarden.core.Kdf
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.manager.UserLogoutManager
import com.x8bit.bitwarden.data.auth.repository.util.toSdkParams
import com.x8bit.bitwarden.data.platform.manager.AppForegroundManager
import com.x8bit.bitwarden.data.platform.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.data.platform.manager.model.AppForegroundState
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.platform.repository.model.VaultTimeout
import com.x8bit.bitwarden.data.platform.repository.model.VaultTimeoutAction
import com.x8bit.bitwarden.data.platform.util.asSuccess
import com.x8bit.bitwarden.data.platform.util.flatMap
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.InitializeCryptoResult
import com.x8bit.bitwarden.data.vault.repository.model.VaultState
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockResult
import com.x8bit.bitwarden.data.vault.repository.util.toVaultUnlockResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val SECONDS_PER_MINUTE = 60
private const val MILLISECONDS_PER_SECOND = 1000

/**
 * Primary implementation [VaultLockManager].
 */
@Suppress("TooManyFunctions", "LongParameterList")
class VaultLockManagerImpl(
    private val authDiskSource: AuthDiskSource,
    private val vaultSdkSource: VaultSdkSource,
    private val settingsRepository: SettingsRepository,
    private val appForegroundManager: AppForegroundManager,
    private val userLogoutManager: UserLogoutManager,
    private val dispatcherManager: DispatcherManager,
    private val elapsedRealtimeMillisProvider: () -> Long = { SystemClock.elapsedRealtime() },
) : VaultLockManager {
    private val unconfinedScope = CoroutineScope(dispatcherManager.unconfined)

    private val activeUserId: String? get() = authDiskSource.userState?.activeUserId
    private val userIds: Set<String> get() = authDiskSource.userState?.accounts?.keys.orEmpty()

    private val mutableVaultStateStateFlow =
        MutableStateFlow(
            VaultState(
                unlockedVaultUserIds = emptySet(),
                unlockingVaultUserIds = emptySet(),
            ),
        )

    override val vaultStateFlow: StateFlow<VaultState>
        get() = mutableVaultStateStateFlow.asStateFlow()

    init {
        observeAppForegroundChanges()
        observeUserSwitchingChanges()
        observeVaultTimeoutChanges()
    }

    override fun isVaultUnlocked(userId: String): Boolean =
        userId in mutableVaultStateStateFlow.value.unlockedVaultUserIds

    override fun isVaultUnlocking(userId: String): Boolean =
        userId in mutableVaultStateStateFlow.value.unlockingVaultUserIds

    override fun lockVault(userId: String) {
        setVaultToLocked(userId = userId)
    }

    override fun lockVaultForCurrentUser() {
        activeUserId?.let {
            lockVault(it)
        }
    }

    override suspend fun unlockVault(
        userId: String,
        email: String,
        kdf: Kdf,
        privateKey: String,
        initUserCryptoMethod: InitUserCryptoMethod,
        organizationKeys: Map<String, String>?,
    ): VaultUnlockResult =
        flow {
            setVaultToUnlocking(userId = userId)
            emit(
                vaultSdkSource
                    .initializeCrypto(
                        userId = userId,
                        request = InitUserCryptoRequest(
                            kdfParams = kdf,
                            email = email,
                            privateKey = privateKey,
                            method = initUserCryptoMethod,
                        ),
                    )
                    .flatMap { result ->
                        // Initialize the SDK for organizations if necessary
                        if (organizationKeys != null &&
                            result is InitializeCryptoResult.Success
                        ) {
                            vaultSdkSource.initializeOrganizationCrypto(
                                userId = userId,
                                request = InitOrgCryptoRequest(
                                    organizationKeys = organizationKeys,
                                ),
                            )
                        } else {
                            result.asSuccess()
                        }
                    }
                    .fold(
                        onFailure = { VaultUnlockResult.GenericError },
                        onSuccess = { initializeCryptoResult ->
                            initializeCryptoResult
                                .toVaultUnlockResult()
                                .also {
                                    if (it is VaultUnlockResult.Success) {
                                        setVaultToUnlocked(userId = userId)
                                    }
                                }
                        },
                    ),
            )
        }
            .onCompletion { setVaultToNotUnlocking(userId = userId) }
            .first()

    private fun setVaultToUnlocked(userId: String) {
        mutableVaultStateStateFlow.update {
            it.copy(
                unlockedVaultUserIds = it.unlockedVaultUserIds + userId,
            )
        }
        // If we are unlocking an account with a timeout of Never, we should make sure to store the
        // auto-unlock key.
        storeUserAutoUnlockKeyIfNecessary(userId = userId)
    }

    private fun setVaultToLocked(userId: String) {
        vaultSdkSource.clearCrypto(userId = userId)
        mutableVaultStateStateFlow.update {
            it.copy(
                unlockedVaultUserIds = it.unlockedVaultUserIds - userId,
            )
        }
        authDiskSource.storeUserAutoUnlockKey(
            userId = userId,
            userAutoUnlockKey = null,
        )
    }

    private fun setVaultToUnlocking(userId: String) {
        mutableVaultStateStateFlow.update {
            it.copy(
                unlockingVaultUserIds = it.unlockingVaultUserIds + userId,
            )
        }
    }

    private fun setVaultToNotUnlocking(userId: String) {
        mutableVaultStateStateFlow.update {
            it.copy(
                unlockingVaultUserIds = it.unlockingVaultUserIds - userId,
            )
        }
    }

    private fun storeUserAutoUnlockKeyIfNecessary(userId: String) {
        val vaultTimeout = settingsRepository.getVaultTimeoutStateFlow(userId = userId).value
        if (
            vaultTimeout == VaultTimeout.Never &&
            authDiskSource.getUserAutoUnlockKey(userId = userId) == null
        ) {
            unconfinedScope.launch {
                vaultSdkSource
                    .getUserEncryptionKey(userId = userId)
                    .getOrNull()
                    ?.let {
                        authDiskSource.storeUserAutoUnlockKey(
                            userId = userId,
                            userAutoUnlockKey = it,
                        )
                    }
            }
        }
    }

    private fun observeAppForegroundChanges() {
        var isFirstForeground = true

        appForegroundManager
            .appForegroundStateFlow
            .onEach { appForegroundState ->
                when (appForegroundState) {
                    AppForegroundState.BACKGROUNDED -> {
                        activeUserId?.let { updateLastActiveTime(userId = it) }
                    }

                    AppForegroundState.FOREGROUNDED -> {
                        userIds.forEach { userId ->
                            // If first foreground, clear the elapsed values so the timeout action
                            // is always performed.
                            if (isFirstForeground) {
                                authDiskSource.storeLastActiveTimeMillis(
                                    userId = userId,
                                    lastActiveTimeMillis = null,
                                )
                            }
                            checkForVaultTimeout(
                                userId = userId,
                                isAppRestart = isFirstForeground,
                            )
                        }
                        isFirstForeground = false
                    }
                }
            }
            .launchIn(unconfinedScope)
    }

    private fun observeUserSwitchingChanges() {
        var lastActiveUserId: String? = null

        authDiskSource
            .userStateFlow
            .mapNotNull { it?.activeUserId }
            .distinctUntilChanged()
            .onEach { activeUserId ->
                val previousActiveUserId = lastActiveUserId
                lastActiveUserId = activeUserId
                if (previousActiveUserId != null &&
                    activeUserId != previousActiveUserId
                ) {
                    handleUserSwitch(
                        previousActiveUserId = previousActiveUserId,
                        currentActiveUserId = activeUserId,
                    )
                }
            }
            .launchIn(unconfinedScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeVaultTimeoutChanges() {
        authDiskSource
            .userStateFlow
            .map { userState -> userState?.accounts?.keys.orEmpty() }
            .distinctUntilChanged()
            .flatMapLatest { userIds ->
                userIds
                    .map { userId -> vaultTimeoutChangesForUserFlow(userId = userId) }
                    .merge()
            }
            .launchIn(unconfinedScope)
    }

    private fun vaultTimeoutChangesForUserFlow(userId: String) =
        settingsRepository
            .getVaultTimeoutStateFlow(userId = userId)
            .onEach { vaultTimeout ->
                handleUserAutoUnlockChanges(
                    userId = userId,
                    vaultTimeout = vaultTimeout,
                )
            }

    private suspend fun handleUserAutoUnlockChanges(
        userId: String,
        vaultTimeout: VaultTimeout,
    ) {
        if (vaultTimeout != VaultTimeout.Never) {
            // Clear the user encryption keys
            authDiskSource.storeUserAutoUnlockKey(
                userId = userId,
                userAutoUnlockKey = null,
            )
            return
        }

        if (isVaultUnlocked(userId = userId)) {
            // Get and save the key if necessary
            val userAutoUnlockKey =
                vaultSdkSource
                    .getUserEncryptionKey(userId = userId)
                    .getOrNull()
            authDiskSource.storeUserAutoUnlockKey(
                userId = userId,
                userAutoUnlockKey = userAutoUnlockKey,
            )
        } else {
            // Retrieve the key. If non-null, unlock the user
            authDiskSource.getUserAutoUnlockKey(userId = userId)?.let {
                val result = unlockVaultForUser(
                    userId = userId,
                    initUserCryptoMethod =
                    InitUserCryptoMethod.DecryptedKey(
                        decryptedUserKey = it,
                    ),
                )
                if (result is VaultUnlockResult.Success) {
                    setVaultToUnlocked(userId = userId)
                }
            }
        }
    }

    /**
     * Handles any vault timeout actions that may need to be performed for the given
     * [previousActiveUserId] and [currentActiveUserId] during an account switch.
     */
    private fun handleUserSwitch(
        previousActiveUserId: String,
        currentActiveUserId: String,
    ) {
        // Check if the user's timeout action should be performed as we switch away.
        checkForVaultTimeout(userId = previousActiveUserId)

        // Set the last active time for the previous user.
        updateLastActiveTime(userId = previousActiveUserId)

        // Check if the vault timeout action should be performed for the current user
        checkForVaultTimeout(userId = currentActiveUserId)

        // Set the last active time for the current user.
        updateLastActiveTime(userId = currentActiveUserId)
    }

    /**
     * Checks the current [VaultTimeout] for the given [userId]. If the given timeout value has
     * been exceeded, the [VaultTimeoutAction] for the given user will be performed.
     */
    @Suppress("ReturnCount")
    private fun checkForVaultTimeout(
        userId: String,
        isAppRestart: Boolean = false,
    ) {
        val currentTimeMillis = elapsedRealtimeMillisProvider()
        val lastActiveTimeMillis =
            authDiskSource
                .getLastActiveTimeMillis(userId = userId)
                ?: 0
        val vaultTimeout =
            settingsRepository.getVaultTimeoutStateFlow(userId = userId).value
        val vaultTimeoutAction =
            settingsRepository.getVaultTimeoutActionStateFlow(userId = userId).value

        val vaultTimeoutInMinutes = when (vaultTimeout) {
            VaultTimeout.Never -> {
                // No action to take for Never timeout.
                return
            }

            VaultTimeout.OnAppRestart -> {
                // If this is an app restart, trigger the timeout action; otherwise ignore.
                if (isAppRestart) 0 else return
            }

            else -> vaultTimeout.vaultTimeoutInMinutes ?: return
        }
        val vaultTimeoutInMillis = vaultTimeoutInMinutes *
            SECONDS_PER_MINUTE *
            MILLISECONDS_PER_SECOND
        if (currentTimeMillis - lastActiveTimeMillis >= vaultTimeoutInMillis) {
            // Perform lock / logout!
            when (vaultTimeoutAction) {
                VaultTimeoutAction.LOCK -> {
                    setVaultToLocked(userId = userId)
                }

                VaultTimeoutAction.LOGOUT -> {
                    setVaultToLocked(userId = userId)
                    userLogoutManager.softLogout(userId = userId)
                }
            }
        }
    }

    /**
     * Sets the "last active time" for the given [userId] to the current time.
     */
    private fun updateLastActiveTime(userId: String) {
        val elapsedRealtimeMillis = elapsedRealtimeMillisProvider()
        authDiskSource.storeLastActiveTimeMillis(
            userId = userId,
            lastActiveTimeMillis = elapsedRealtimeMillis,
        )
    }

    @Suppress("ReturnCount")
    private suspend fun unlockVaultForUser(
        userId: String,
        initUserCryptoMethod: InitUserCryptoMethod,
    ): VaultUnlockResult {
        val account = authDiskSource.userState?.accounts?.get(userId)
            ?: return VaultUnlockResult.InvalidStateError
        val privateKey = authDiskSource.getPrivateKey(userId = userId)
            ?: return VaultUnlockResult.InvalidStateError
        val organizationKeys = authDiskSource
            .getOrganizationKeys(userId = userId)
        return unlockVault(
            userId = userId,
            email = account.profile.email,
            kdf = account.profile.toSdkParams(),
            privateKey = privateKey,
            initUserCryptoMethod = initUserCryptoMethod,
            organizationKeys = organizationKeys,
        )
    }
}