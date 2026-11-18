package com.example.opendash.auto

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class GitHubUpdatesTest {
    private fun metadata():JSONObject=JSONObject("""{"tag_name":"v0.1.49","draft":false,"prerelease":false,"assets":[{"name":"Rota-Auto-0.1.49.apk","state":"uploaded","size":12500000,"digest":"sha256:${"a".repeat(64)}","browser_download_url":"https://github.com/MatheusCastellar/Rota-Auto/releases/download/v0.1.49/Rota-Auto-0.1.49.apk"}]}""")
    @Test fun versionsAreNumericAndStableOnly() {
        assertTrue(GitHubUpdates.newer("v0.1.100","0.1.49"))
        assertFalse(GitHubUpdates.newer("0.1.9","0.1.49"))
        for(v in listOf("0.1.49","v0.1.49.0","v0.1.50-beta","x","999999999999.1")) assertFalse(GitHubUpdates.newer(v,"0.1.49"))
        assertEquals("0.1.49",GitHubUpdates.parse(metadata().toString(),"0.1.48")!!.version)
        assertNull(GitHubUpdates.parse(metadata().toString(),"0.1.49"))
        assertNull(GitHubUpdates.parse(metadata().put("prerelease",true).toString(),"0.1.48"))
    }
    @Test fun rejectsWrongRepoMissingDigestOversizeAndAmbiguousApks() {
        for((key,value) in listOf("digest" to "", "size" to 0, "size" to 999999999,
            "browser_download_url" to "https://github.com/attacker/Rota-Auto/releases/download/v0.1.49/a.apk",
            "browser_download_url" to "http://github.com/MatheusCastellar/Rota-Auto/releases/download/v0.1.49/a.apk")) {
            val data=metadata(); data.getJSONArray("assets").getJSONObject(0).put(key,value)
            assertTrue(runCatching { GitHubUpdates.parse(data.toString(),"0.1.48") }.isFailure)
        }
        val many=metadata(); many.getJSONArray("assets").put(many.getJSONArray("assets").getJSONObject(0))
        assertTrue(runCatching { GitHubUpdates.parse(many.toString(),"0.1.48") }.isFailure)
    }
}
