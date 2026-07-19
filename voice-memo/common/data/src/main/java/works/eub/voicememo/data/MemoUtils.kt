package works.eub.voicememo.data

object MemoUtils {
    fun generateTitle(content: String): String {
        val trimmed = content.trim()
        return if (trimmed.length > 30) trimmed.take(30) + "..." else trimmed
    }
}
