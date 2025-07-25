package com.x8bit.bitwarden.ui.vault.feature.vault.util

import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.ui.vault.feature.vault.model.VaultFilterData
import com.x8bit.bitwarden.ui.vault.feature.vault.model.VaultFilterType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VaultFilterDataExtensionsTest {
    @Test
    fun `toAppBarTitle for a null value should return My Vault`() {
        assertEquals(
            BitwardenString.my_vault.asText(),
            (null as VaultFilterData?).toAppBarTitle(),
        )
    }

    @Test
    fun `toAppBarTitle for a non-null value should return Vaults`() {
        assertEquals(
            BitwardenString.vaults.asText(),
            VaultFilterData(
                selectedVaultFilterType = VaultFilterType.MyVault,
                vaultFilterTypes = listOf(
                    VaultFilterType.AllVaults,
                    VaultFilterType.MyVault,
                ),
            )
                .toAppBarTitle(),
        )
    }
}
