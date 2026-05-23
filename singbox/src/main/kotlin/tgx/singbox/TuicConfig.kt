package tgx.singbox

data class TuicConfig(
    val server: String,
    val port: Int,
    val uuid: String,
    val password: String,
    val congestionControl: String = "bbr",
    val tlsInsecure: Boolean = true
) {
    fun identity(): String = "$server:$port:$uuid"
}
