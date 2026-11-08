/*
 *    Copyright 2024 Adetunji Dahunsi
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.tunjid.heron.compose

import com.tunjid.heron.data.core.models.ModelUrlSafeBase64
import com.tunjid.heron.data.core.types.GenericUri
import com.tunjid.heron.ui.text.links
import com.tunjid.treenav.strings.routeOf
import com.tunjid.treenav.strings.routeParserFrom
import com.tunjid.treenav.strings.routeString
import com.tunjid.treenav.strings.urlRouteMatcher
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class SharedTextStateTest {
    private val parser = routeParserFrom(
        urlRouteMatcher(
            routePattern = "/compose",
            routeMapper = { params -> routeOf(params = params) },
        ),
    )

    @Test
    fun shareStartsAsEditableTextWithTheCursorAtTheEnd() {
        val text = "A component 👋\nhttps://preview.coo.ee/p/example?theme=Dark&size=320"
        val state = State(
            route = requireNotNull(
                parser.parse(
                    pathAndQueries = routeString(
                        path = "/compose",
                        queryParams = mapOf("sharedText" to listOf(ModelUrlSafeBase64.encode(text.encodeToByteArray()))),
                    ),
                ),
            ),
        )
        assertEquals(text, state.postText.text)
        assertEquals(text.length, state.postText.selection.start)
        assertEquals(text.length, state.postText.selection.end)
        assertEquals(null, state.postType)
        assertTrue(state.postText.annotatedString.spanStyles.isNotEmpty())
        assertEquals(1, state.postText.annotatedString.links().size)
        val url = "https://preview.coo.ee/p/example?theme=Dark&size=320"
        assertEquals(Action.UriDetected(url = url), state.sharedLinkPreviewAction())
        // Dismissing a card must survive producer restart: no automatic re-attachment.
        assertNull(state.copy(dismissedUri = GenericUri(url)).sharedLinkPreviewAction())
    }

    @Test
    fun ordinaryComposerStillStartsEmpty() {
        assertEquals("", State(route = routeOf(path = "/compose")).postText.text)
    }
}
