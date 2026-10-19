package calebxzhou.rdi.client.auth

import calebxzhou.rdi.common.model.RAccount
import org.bson.types.ObjectId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountSessionStoreTest {
    private lateinit var originalAccount: RAccount

    @BeforeTest
    fun saveAccount() {
        originalAccount = AccountSessionStore.current.copyAccount()
    }

    @AfterTest
    fun restoreAccount() {
        AccountSessionStore.current = originalAccount
    }

    @Test
    fun updateJwtUpdatesCurrentSession() {
        AccountSessionStore.current = testAccount(jwt = "old-jwt")

        AccountSessionStore.updateJwt("new-jwt")

        assertEquals("new-jwt", AccountSessionStore.account.value.jwt)
        assertTrue(AccountSessionStore.isLoggedIn)
    }

    @Test
    fun equivalentLoginRefreshesJwt() {
        AccountSessionStore.current = testAccount(jwt = "old-jwt")

        AccountSessionStore.current = testAccount(jwt = "new-jwt")

        assertEquals("new-jwt", AccountSessionStore.current.jwt)
    }

    @Test
    fun updateClothPreservesJwt() {
        AccountSessionStore.current = testAccount(jwt = "jwt")
        val cloth = RAccount.Cloth(isSlim = false, skin = "new-skin", cape = "new-cape")

        AccountSessionStore.updateCloth(cloth)

        assertEquals(cloth, AccountSessionStore.current.cloth)
        assertEquals("jwt", AccountSessionStore.current.jwt)
    }

    @Test
    fun logoutResetsAccountState() {
        AccountSessionStore.current = testAccount(jwt = "jwt")

        AccountSessionStore.logout()

        assertEquals(RAccount.DEFAULT, AccountSessionStore.current)
        assertNull(AccountSessionStore.current.jwt)
        assertFalse(AccountSessionStore.isLoggedIn)
    }

    private fun testAccount(jwt: String) = RAccount(
        _id = ObjectId("68b314bbadaf52ddab96b5ed"),
        name = "测试账号",
        pwd = "password",
        qq = "123456"
    ).also { it.jwt = jwt }

    private fun RAccount.copyAccount() = copy(cloth = cloth.copy()).also { it.jwt = jwt }
}
