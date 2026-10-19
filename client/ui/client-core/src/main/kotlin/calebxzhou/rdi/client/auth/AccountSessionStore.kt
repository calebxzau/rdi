package calebxzhou.rdi.client.auth

import calebxzhou.rdi.common.model.RAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AccountSessionStore {
    private val _account = MutableStateFlow(defaultAccount())
    val account = _account.asStateFlow()

    var current: RAccount
        get() = _account.value
        set(value) {
            if (_account.value == value) {
                _account.value.jwt = value.jwt
            } else {
                _account.value = value
            }
        }

    val isLoggedIn: Boolean
        get() = current._id != RAccount.DEFAULT._id

    fun updateJwt(jwt: String?) {
        current.jwt = jwt
    }

    fun updateCloth(cloth: RAccount.Cloth) {
        val account = current
        current = account.copy(cloth = cloth.copy()).also { it.jwt = account.jwt }
    }

    fun logout() {
        current = defaultAccount()
    }

    private fun defaultAccount() = RAccount.DEFAULT.copy(cloth = RAccount.DEFAULT.cloth.copy())
}
