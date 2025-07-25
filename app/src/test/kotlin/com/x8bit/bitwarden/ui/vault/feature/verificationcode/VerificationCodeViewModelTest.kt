package com.x8bit.bitwarden.ui.vault.feature.verificationcode

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.bitwarden.core.data.repository.model.DataState
import com.bitwarden.data.repository.model.Environment
import com.bitwarden.data.repository.util.baseIconUrl
import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.Text
import com.bitwarden.ui.util.asText
import com.bitwarden.ui.util.concat
import com.bitwarden.vault.CipherRepromptType
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.auth.repository.model.ValidatePasswordResult
import com.x8bit.bitwarden.data.platform.manager.clipboard.BitwardenClipboardManager
import com.x8bit.bitwarden.data.platform.repository.EnvironmentRepository
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockCipherView
import com.x8bit.bitwarden.data.vault.manager.model.VerificationCodeItem
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.ui.vault.feature.vault.model.VaultFilterType
import com.x8bit.bitwarden.ui.vault.feature.vault.util.toLoginIconData
import com.x8bit.bitwarden.ui.vault.feature.verificationcode.util.createVerificationCodeItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VerificationCodeViewModelTest : BaseViewModelTest() {

    private val clipboardManager: BitwardenClipboardManager = mockk {
        every { setText(text = any<String>(), toastDescriptorOverride = any<Text>()) } just runs
    }

    private val mutableAuthCodeFlow =
        MutableStateFlow<DataState<List<VerificationCodeItem>>>(DataState.Loading)

    private val vaultRepository: VaultRepository = mockk {
        every { vaultFilterType } returns VaultFilterType.AllVaults
        every { getAuthCodesFlow() } returns mutableAuthCodeFlow.asStateFlow()
        every { sync(forced = any()) } just runs
    }

    private val environmentRepository: EnvironmentRepository = mockk {
        every { environment } returns Environment.Us
        every { environmentStateFlow } returns mockk()
    }

    private val mockUserAccount: UserState.Account = mockk {
        every { isPremium } returns true
        every { hasMasterPassword } returns true
    }

    private val mockUserState: UserState = mockk {
        every { activeAccount } returns mockUserAccount
    }

    private val mutableUserStateFlow: MutableStateFlow<UserState> = MutableStateFlow(mockUserState)

    private val authRepository: AuthRepository = mockk {
        every { userStateFlow } returns mutableUserStateFlow
    }

    private val mutablePullToRefreshEnabledFlow = MutableStateFlow(false)
    private val mutableIsIconLoadingDisabledFlow = MutableStateFlow(false)
    private val settingsRepository: SettingsRepository = mockk {
        every { isIconLoadingDisabled } returns false
        every { isIconLoadingDisabledFlow } returns mutableIsIconLoadingDisabledFlow
        every { getPullToRefreshEnabledFlow() } returns mutablePullToRefreshEnabledFlow
    }
    private val initialState = createVerificationCodeState()

    @AfterEach
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `initial state should be correct`() = runTest {
        val viewModel = createViewModel()
        viewModel.stateFlow.test {
            assertEquals(
                initialState, awaitItem(),
            )
        }
    }

    @Test
    fun `on BackClick should emit onNavigateBack`() = runTest {
        val viewModel = createViewModel()
        viewModel.eventFlow.test {
            viewModel.trySendAction(VerificationCodeAction.BackClick)
            assertEquals(VerificationCodeEvent.NavigateBack, awaitItem())
        }
    }

    @Test
    fun `onCopyClick should call setText on the ClipboardManager`() = runTest {
        val authCode = "123456"
        val viewModel = createViewModel()

        viewModel.trySendAction(VerificationCodeAction.CopyClick(authCode))

        verify(exactly = 1) {
            clipboardManager.setText(
                text = authCode,
                toastDescriptorOverride = BitwardenString.verification_code_totp.asText(),
            )
        }
    }

    @Test
    fun `ItemClick for vault item should emit NavigateToVaultItem`() = runTest {
        val viewModel = createViewModel()
        viewModel.eventFlow.test {
            viewModel.trySendAction(VerificationCodeAction.ItemClick(id = "mock"))
            assertEquals(VerificationCodeEvent.NavigateToVaultItem(id = "mock"), awaitItem())
        }
    }

    @Test
    fun `MasterPasswordSubmit should validate the password and display an error on failure`() {
        val viewModel = createViewModel()
        val password = "password_1234"
        val throwable = Throwable("Error!")
        coEvery {
            authRepository.validatePassword(password = password)
        } returns ValidatePasswordResult.Error(error = throwable)
        viewModel.trySendAction(
            action = VerificationCodeAction.MasterPasswordSubmit(
                cipherId = "cipher_id",
                password = password,
            ),
        )
        assertEquals(
            viewModel.stateFlow.value,
            initialState.copy(
                dialogState = VerificationCodeState.DialogState.Error(
                    title = null,
                    message = BitwardenString.generic_error_message.asText(),
                    throwable = throwable,
                ),
            ),
        )
        coVerify(exactly = 1) {
            authRepository.validatePassword(password = password)
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `MasterPasswordSubmit should validate the password and display an error on invalid password`() {
        val viewModel = createViewModel()
        val password = "password_1234"
        coEvery {
            authRepository.validatePassword(password = password)
        } returns ValidatePasswordResult.Success(isValid = false)
        viewModel.trySendAction(
            action = VerificationCodeAction.MasterPasswordSubmit(
                cipherId = "cipher_id",
                password = password,
            ),
        )
        assertEquals(
            viewModel.stateFlow.value,
            initialState.copy(
                dialogState = VerificationCodeState.DialogState.Error(
                    title = null,
                    message = BitwardenString.invalid_master_password.asText(),
                ),
            ),
        )
        coVerify(exactly = 1) {
            authRepository.validatePassword(password = password)
        }
    }

    @Test
    fun `MasterPasswordSubmit should validate the password and emit NavigateToVaultItem`() =
        runTest {
            val viewModel = createViewModel()
            val password = "password_1234"
            val cipherId = "cipher_id"
            coEvery {
                authRepository.validatePassword(password = password)
            } returns ValidatePasswordResult.Success(isValid = true)
            viewModel.eventFlow.test {
                viewModel.trySendAction(
                    action = VerificationCodeAction.MasterPasswordSubmit(
                        cipherId = cipherId,
                        password = password,
                    ),
                )
                assertEquals(VerificationCodeEvent.NavigateToVaultItem(id = cipherId), awaitItem())
            }
            coVerify(exactly = 1) {
                authRepository.validatePassword(password = password)
            }
        }

    @Test
    fun `LockClick should call lockVaultForCurrentUser`() {
        every { vaultRepository.lockVaultForCurrentUser(any()) } just runs
        val viewModel = createViewModel()

        viewModel.trySendAction(VerificationCodeAction.LockClick)

        verify(exactly = 1) {
            vaultRepository.lockVaultForCurrentUser(isUserInitiated = true)
        }
    }

    @Test
    fun `RefreshClick should sync`() = runTest {
        val viewModel = createViewModel()
        viewModel.trySendAction(VerificationCodeAction.RefreshClick)
        verify { vaultRepository.sync(forced = true) }
    }

    @Test
    fun `SearchIconClick should emit NavigateToVaultSearchScreen`() = runTest {
        val viewModel = createViewModel()
        viewModel.eventFlow.test {
            viewModel.trySendAction(VerificationCodeAction.SearchIconClick)
            assertEquals(VerificationCodeEvent.NavigateToVaultSearchScreen, awaitItem())
        }
    }

    @Test
    fun `SyncClick should display the loading dialog and call sync`() {
        val viewModel = createViewModel()

        viewModel.trySendAction(VerificationCodeAction.SyncClick)

        assertEquals(
            initialState.copy(
                dialogState = VerificationCodeState.DialogState.Loading(
                    message = BitwardenString.syncing.asText(),
                ),
            ),
            viewModel.stateFlow.value,
        )
        verify(exactly = 1) {
            vaultRepository.sync(forced = true)
        }
    }

    @Test
    fun `AuthCodeFlow Pending with data should update state to Content`() {
        setupMockUri()

        val viewModel = createViewModel()

        mutableAuthCodeFlow.tryEmit(
            value = DataState.Pending(
                data = listOf(
                    createVerificationCodeItem(number = 1),
                    createVerificationCodeItem(number = 2).copy(hasPasswordReprompt = true),
                ),
            ),
        )

        assertEquals(
            createVerificationCodeState(
                viewState = VerificationCodeState.ViewState.Content(
                    createDisplayItemList(),
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `AuthCodeFlow Pending with no data should call NavigateBack to go to the vault screen`() =
        runTest {
            setupMockUri()

            val viewModel = createViewModel()

            mutableAuthCodeFlow.tryEmit(
                value = DataState.Pending(
                    data = listOf(),
                ),
            )

            viewModel.eventFlow.test {
                assertEquals(VerificationCodeEvent.NavigateBack, awaitItem())
            }
        }

    @Test
    fun `AuthCodeFlow Error with data should update state to Content`() = runTest {
        setupMockUri()

        val viewModel = createViewModel()

        mutableAuthCodeFlow.tryEmit(
            value = DataState.Error(
                data = listOf(
                    createVerificationCodeItem(number = 1),
                    createVerificationCodeItem(number = 2).copy(hasPasswordReprompt = true),
                ),
                error = IllegalStateException(),
            ),
        )

        assertEquals(
            createVerificationCodeState(
                viewState = VerificationCodeState.ViewState.Content(
                    createDisplayItemList(),
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `AuthCodeFlow Error with no data should call NavigateBack to go to the vault screen`() =
        runTest {
            setupMockUri()

            val viewModel = createViewModel()

            mutableAuthCodeFlow.tryEmit(
                value = DataState.Error(
                    data = listOf(),
                    error = IllegalStateException(),
                ),
            )

            viewModel.eventFlow.test {
                assertEquals(VerificationCodeEvent.NavigateBack, awaitItem())
            }
        }

    @Test
    fun `AuthCodeFlow Error with null data should show error screen`() = runTest {
        setupMockUri()

        val viewModel = createViewModel()

        mutableAuthCodeFlow.tryEmit(
            value = DataState.Error(
                data = null,
                error = IllegalStateException(),
            ),
        )

        assertEquals(
            createVerificationCodeState(
                viewState = VerificationCodeState.ViewState.Error(
                    message = BitwardenString.generic_error_message.asText(),
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `AuthCodeFlow NoNetwork with empty data should call NavigateBack to go to the vault screen`() =
        runTest {
            val viewModel = createViewModel()

            mutableAuthCodeFlow.tryEmit(
                DataState.NoNetwork(emptyList()),
            )

            viewModel.eventFlow.test {
                assertEquals(VerificationCodeEvent.NavigateBack, awaitItem())
            }
        }

    @Test
    fun `AuthCodeFlow NoNetwork with null should update state to Error`() = runTest {
        val viewModel = createViewModel()

        mutableAuthCodeFlow.tryEmit(
            DataState.NoNetwork(null),
        )

        assertEquals(
            createVerificationCodeState(
                viewState = VerificationCodeState.ViewState.Error(
                    message = BitwardenString.internet_connection_required_title
                        .asText()
                        .concat(
                            " ".asText(),
                            BitwardenString.internet_connection_required_message.asText(),
                        ),
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `AuthCodeFlow NoNetwork with data should update state to Content`() = runTest {
        setupMockUri()

        val viewModel = createViewModel()

        mutableAuthCodeFlow.tryEmit(
            value = DataState.NoNetwork(
                data = listOf(
                    createVerificationCodeItem(number = 1),
                    createVerificationCodeItem(number = 2).copy(hasPasswordReprompt = true),
                ),
            ),
        )

        assertEquals(
            createVerificationCodeState(
                viewState = VerificationCodeState.ViewState.Content(
                    createDisplayItemList(),
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `AuthCodeFlow Loaded with empty data should call NavigateBack to go the vault screen`() =
        runTest {
            val viewModel = createViewModel()

            mutableAuthCodeFlow.tryEmit(
                DataState.Loaded(emptyList()),
            )

            viewModel.eventFlow.test {
                assertEquals(VerificationCodeEvent.NavigateBack, awaitItem())
            }
        }

    @Test
    fun `AuthCodeFlow Loaded with valid items should update ViewState to content`() = runTest {
        setupMockUri()

        val viewModel = createViewModel()

        mutableAuthCodeFlow.tryEmit(
            value = DataState.Loaded(
                data = listOf(
                    createVerificationCodeItem(number = 1),
                    createVerificationCodeItem(number = 2).copy(hasPasswordReprompt = true),
                ),
            ),
        )

        assertEquals(
            createVerificationCodeState(
                viewState = VerificationCodeState.ViewState.Content(
                    createDisplayItemList(),
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `AuthCodeFlow Loaded with valid items should update ViewState to content but leave the error dialog state alone`() =
        runTest {
            setupMockUri()
            val state = createVerificationCodeState(
                dialogState = VerificationCodeState.DialogState.Error(
                    title = null,
                    message = "Test".asText(),
                ),
            )
            val viewModel = createViewModel(state = state)

            mutableAuthCodeFlow.tryEmit(
                value = DataState.Loaded(
                    data = listOf(
                        createVerificationCodeItem(number = 1),
                        createVerificationCodeItem(number = 2).copy(hasPasswordReprompt = true),
                    ),
                ),
            )

            assertEquals(
                state.copy(
                    viewState = VerificationCodeState.ViewState.Content(
                        createDisplayItemList(),
                    ),
                ),
                viewModel.stateFlow.value,
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `AuthCodeFlow Loaded with valid items should update ViewState to content and clear the loading dialog state`() =
        runTest {
            setupMockUri()
            val state = createVerificationCodeState(
                dialogState = VerificationCodeState.DialogState.Loading(
                    message = "Test".asText(),
                ),
            )
            val viewModel = createViewModel(state = state)

            mutableAuthCodeFlow.tryEmit(
                value = DataState.Loaded(
                    data = listOf(
                        createVerificationCodeItem(number = 1),
                        createVerificationCodeItem(number = 2).copy(hasPasswordReprompt = true),
                    ),
                ),
            )

            assertEquals(
                state.copy(
                    viewState = VerificationCodeState.ViewState.Content(
                        createDisplayItemList(),
                    ),
                    dialogState = null,
                ),
                viewModel.stateFlow.value,
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `AuthCodeState Loaded with non premium user and no org TOTP enabled should cause navigate back`() =
        runTest {
            setupMockUri()
            every { mockUserAccount.isPremium } returns false
            val viewModel = createViewModel()
            mutableAuthCodeFlow.tryEmit(
                value = DataState.Loaded(
                    data = listOf(
                        createVerificationCodeItem(number = 1),
                        createVerificationCodeItem(number = 2).copy(hasPasswordReprompt = true),
                    ),
                ),
            )

            viewModel.eventFlow.test {
                assertEquals(VerificationCodeEvent.NavigateBack, awaitItem())
            }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `AuthCodeState Loaded with non premium user and one org TOTP enabled should return correct state`() =
        runTest {
            setupMockUri()
            every { mockUserAccount.isPremium } returns false
            val viewModel = createViewModel()
            mutableAuthCodeFlow.tryEmit(
                value = DataState.Loaded(
                    data = listOf(
                        createVerificationCodeItem(number = 1).copy(orgUsesTotp = true),
                        createVerificationCodeItem(number = 2).copy(hasPasswordReprompt = true),
                    ),
                ),
            )

            val displayItems = (viewModel.stateFlow.value.viewState as?
                VerificationCodeState.ViewState.Content)
                ?.verificationCodeDisplayItems
            assertEquals(1, displayItems?.size)
        }

    @Test
    fun `AuthCodeFlow Loading should update state to Loading`() = runTest {
        mutableAuthCodeFlow.tryEmit(value = DataState.Loading)

        val viewModel = createViewModel()

        assertEquals(
            createVerificationCodeState(viewState = VerificationCodeState.ViewState.Loading),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `icon loading state updates should update isIconLoadingDisabled`() = runTest {
        val viewModel = createViewModel()

        assertFalse(viewModel.stateFlow.value.isIconLoadingDisabled)

        mutableIsIconLoadingDisabledFlow.value = true
        assertTrue(viewModel.stateFlow.value.isIconLoadingDisabled)
    }

    @Test
    fun `RefreshPull should call vault repository sync`() {
        val viewModel = createViewModel()

        viewModel.trySendAction(VerificationCodeAction.RefreshPull)

        verify(exactly = 1) {
            vaultRepository.sync(forced = false)
        }
    }

    @Test
    fun `PullToRefreshEnableReceive should update isPullToRefreshEnabled`() = runTest {
        val viewModel = createViewModel()

        viewModel.trySendAction(
            VerificationCodeAction.Internal.PullToRefreshEnableReceive(
                isPullToRefreshEnabled = true,
            ),
        )

        assertEquals(
            initialState.copy(isPullToRefreshSettingEnabled = true),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `DismissDialog should clear the dialog state`() = runTest {
        val defaultState = initialState.copy(
            dialogState = VerificationCodeState.DialogState.Error(
                title = null,
                message = "Test".asText(),
            ),
        )
        val viewModel = createViewModel(state = defaultState)
        viewModel.stateFlow.test {
            assertEquals(defaultState, awaitItem())
            viewModel.trySendAction(VerificationCodeAction.DismissDialog)
            assertEquals(initialState, awaitItem())
        }
    }

    private fun setupMockUri() {
        mockkStatic(Uri::class)
        val uriMock = mockk<Uri>()
        every { Uri.parse(any()) } returns uriMock
        every { uriMock.host } returns "www.mockuri.com"
    }

    private fun createViewModel(
        state: VerificationCodeState? = null,
    ): VerificationCodeViewModel =
        VerificationCodeViewModel(
            savedStateHandle = SavedStateHandle().apply {
                set(key = "state", value = state)
            },
            clipboardManager = clipboardManager,
            vaultRepository = vaultRepository,
            environmentRepository = environmentRepository,
            settingsRepository = settingsRepository,
            authRepository = authRepository,
        )

    private fun createVerificationCodeState(
        viewState: VerificationCodeState.ViewState = VerificationCodeState.ViewState.Loading,
        dialogState: VerificationCodeState.DialogState? = null,
    ): VerificationCodeState = VerificationCodeState(
        viewState = viewState,
        vaultFilterType = vaultRepository.vaultFilterType,
        isIconLoadingDisabled = settingsRepository.isIconLoadingDisabled,
        baseIconUrl = environmentRepository.environment.environmentUrlData.baseIconUrl,
        dialogState = dialogState,
        isPullToRefreshSettingEnabled = settingsRepository.getPullToRefreshEnabledFlow().value,
        isRefreshing = false,
        hasMasterPassword = true,
    )

    private fun createDisplayItemList() = listOf(
        createMockCipherView(
            number = 1,
            isDeleted = false,
        )
            .let { cipherView ->
                VerificationCodeDisplayItem(
                    id = cipherView.id.toString(),
                    authCode = "123456",
                    hideAuthCode = false,
                    label = cipherView.name,
                    supportingLabel = cipherView.login?.username,
                    periodSeconds = 30,
                    timeLeftSeconds = 30,
                    startIcon = cipherView.login?.uris.toLoginIconData(
                        isIconLoadingDisabled = initialState.isIconLoadingDisabled,
                        baseIconUrl = initialState.baseIconUrl,
                        usePasskeyDefaultIcon = false,
                    ),
                )
            },
        createMockCipherView(
            number = 2,
            isDeleted = false,
            repromptType = CipherRepromptType.PASSWORD,
        )
            .let { cipherView ->
                VerificationCodeDisplayItem(
                    id = cipherView.id.toString(),
                    authCode = "123456",
                    hideAuthCode = true,
                    label = cipherView.name,
                    supportingLabel = cipherView.login?.username,
                    periodSeconds = 30,
                    timeLeftSeconds = 30,
                    startIcon = cipherView.login?.uris.toLoginIconData(
                        isIconLoadingDisabled = initialState.isIconLoadingDisabled,
                        baseIconUrl = initialState.baseIconUrl,
                        usePasskeyDefaultIcon = false,
                    ),
                )
            },
    )
}
