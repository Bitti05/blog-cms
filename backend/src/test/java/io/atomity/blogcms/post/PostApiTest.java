package io.atomity.blogcms.post;

import com.fasterxml.jackson.databind.JsonNode;
import io.atomity.blogcms.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PostApiTest extends IntegrationTest {

    // ── Builders ───────────────────────────────────────────────────────────

    static Map<String, Object> doc(String... paragraphs) {
        List<Map<String, Object>> content = new java.util.ArrayList<>();
        for (String text : paragraphs) {
            content.add(Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", text))));
        }
        return Map.of("type", "doc", "content", content);
    }

    static Map<String, Object> postBody(String title, Map<String, Object> content) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("content", content);
        return body;
    }

    private JsonNode createPost(String title, String... paragraphs) throws Exception {
        return data(call(post("/api/v1/posts"), postBody(title, doc(paragraphs))).andExpect(status().isCreated()));
    }

    private String createAuthor(String name) throws Exception {
        return data(call(post("/api/v1/authors"), Map.of("name", name)).andExpect(status().isCreated())).path("id").asText();
    }

    private String createTag(String name) throws Exception {
        return data(call(post("/api/v1/tags"), Map.of("name", name)).andExpect(status().isCreated())).path("id").asText();
    }

    // ── Create & read ──────────────────────────────────────────────────────

    @Test
    void createStartsAsDraftWithDerivedSlugAndCounts() throws Exception {
        JsonNode created = createPost("Hello, Wörld!", "One two three four.", "Five six.");

        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("slug").asText()).isEqualTo("hello-world");
        assertThat(created.path("wordCount").asInt()).isEqualTo(6);
        assertThat(created.path("readingTimeMinutes").asInt()).isEqualTo(1);
        assertThat(created.path("publishedAt").isNull()).isTrue();
        assertThat(created.path("content").path("type").asText()).isEqualTo("doc");

        call(get("/api/v1/posts/" + created.path("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Hello, Wörld!"))
                .andExpect(jsonPath("$.data.content.content[0].content[0].text").value("One two three four."));
    }

    @Test
    void untitledDraftGetsAPlaceholderSlug() throws Exception {
        JsonNode created = createPost("");
        assertThat(created.path("slug").asText()).startsWith("draft-");
    }

    @Test
    void duplicateSlugIsRejected() throws Exception {
        createPost("Same title", "text");
        call(post("/api/v1/posts"), postBody("Same title", doc("other")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].errorCode").value("SLUG_TAKEN"));
    }

    @Test
    void invalidFieldsAreRejected() throws Exception {
        Map<String, Object> body = postBody("x".repeat(201), doc("text"));
        body.put("slug", "Not A Slug");
        call(post("/api/v1/posts"), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("VALIDATION_ERROR"));

        call(post("/api/v1/posts"), postBody("Bad body", Map.of("type", "paragraph")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("INVALID_CONTENT"));
    }

    @Test
    void unsafeLinksAreRejected() throws Exception {
        Map<String, Object> link = Map.of("type", "link", "attrs", Map.of("href", "javascript:alert(1)"));
        Map<String, Object> content = Map.of("type", "doc", "content", List.of(Map.of("type", "paragraph",
                "content", List.of(Map.of("type", "text", "text", "click", "marks", List.of(link))))));
        call(post("/api/v1/posts"), postBody("XSS", content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("UNSAFE_LINK"));
    }

    @Test
    void authorsKeepTheirOrderAndUnknownIdsFail() throws Exception {
        String lena = createAuthor("Lena");
        String rahul = createAuthor("Rahul");
        String tag = createTag("FinOps");

        Map<String, Object> body = postBody("With people", doc("text"));
        body.put("authorIds", List.of(rahul, lena));
        body.put("tagIds", List.of(tag));
        JsonNode created = data(call(post("/api/v1/posts"), body).andExpect(status().isCreated()));
        assertThat(created.path("authors").findValuesAsText("name")).containsExactly("Rahul", "Lena");
        assertThat(created.path("tags").get(0).path("slug").asText()).isEqualTo("finops");

        body.put("title", "Ghost author");
        body.put("authorIds", List.of("00000000-0000-0000-0000-000000000000"));
        call(post("/api/v1/posts"), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("UNKNOWN_REFERENCE"));
    }

    // ── Update & optimistic locking ────────────────────────────────────────

    @Test
    void updateRequiresCurrentVersion() throws Exception {
        JsonNode created = createPost("Versioned", "first");
        String id = created.path("id").asText();
        long version = created.path("version").asLong();

        Map<String, Object> edit = postBody("Versioned v2", doc("second"));
        edit.put("slug", "versioned");
        edit.put("version", version);
        JsonNode updated = data(call(put("/api/v1/posts/" + id), edit).andExpect(status().isOk()));
        assertThat(updated.path("title").asText()).isEqualTo("Versioned v2");
        assertThat(updated.path("version").asLong()).isGreaterThan(version);

        // A second save from an editor still holding the old version is refused.
        call(put("/api/v1/posts/" + id), edit)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].errorCode").value("VERSION_CONFLICT"));

        edit.remove("version");
        call(put("/api/v1/posts/" + id), edit).andExpect(status().isBadRequest());
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Test
    void publishRequiresTitleAndContent() throws Exception {
        String empty = createPost("").path("id").asText();
        call(post("/api/v1/posts/" + empty + "/publish"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].errorCode").value("NOT_PUBLISHABLE"))
                .andExpect(jsonPath("$.errors[0].message").value("Add a title and some content before publishing."));
    }

    @Test
    void publishKeepsFirstPublicationDate() throws Exception {
        String id = createPost("Lifecycle", "Some words here.").path("id").asText();

        JsonNode published = data(call(post("/api/v1/posts/" + id + "/publish")).andExpect(status().isOk()));
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        String firstPublishedAt = published.path("publishedAt").asText();
        assertThat(firstPublishedAt).isNotBlank();

        assertThat(data(call(post("/api/v1/posts/" + id + "/unpublish"))).path("status").asText()).isEqualTo("DRAFT");
        assertThat(data(call(post("/api/v1/posts/" + id + "/archive"))).path("status").asText()).isEqualTo("ARCHIVED");
        JsonNode republished = data(call(post("/api/v1/posts/" + id + "/publish")));
        assertThat(republished.path("publishedAt").asText()).isEqualTo(firstPublishedAt);
    }

    @Test
    void duplicateCreatesUnpublishedCopyWithFreeSlug() throws Exception {
        String id = createPost("Original", "Body text.").path("id").asText();
        call(post("/api/v1/posts/" + id + "/publish")).andExpect(status().isOk());

        JsonNode first = data(call(post("/api/v1/posts/" + id + "/duplicate")).andExpect(status().isCreated()));
        JsonNode second = data(call(post("/api/v1/posts/" + id + "/duplicate")).andExpect(status().isCreated()));
        assertThat(first.path("slug").asText()).isEqualTo("original-copy");
        assertThat(second.path("slug").asText()).isEqualTo("original-copy-2");
        assertThat(first.path("status").asText()).isEqualTo("DRAFT");
        assertThat(first.path("title").asText()).isEqualTo("Original (copy)");
        assertThat(first.path("wordCount").asInt()).isEqualTo(2);
    }

    @Test
    void deleteRemovesPost() throws Exception {
        String id = createPost("Doomed", "text").path("id").asText();
        call(delete("/api/v1/posts/" + id)).andExpect(status().isNoContent());
        call(get("/api/v1/posts/" + id)).andExpect(status().isNotFound());
    }

    // ── Listing ────────────────────────────────────────────────────────────

    @Test
    void listFiltersSearchesSortsAndPaginates() throws Exception {
        String tag = createTag("Kubernetes");
        Map<String, Object> tagged = postBody("Rightsizing pods", doc("Kubernetes requests and limits."));
        tagged.put("tagIds", List.of(tag));
        call(post("/api/v1/posts"), tagged).andExpect(status().isCreated());
        createPost("Carbon footprint", "Grid mix matters.");
        String published = createPost("Spot instances", "Interruptions happen.").path("id").asText();
        call(post("/api/v1/posts/" + published + "/publish")).andExpect(status().isOk());

        // Substring search covers the body text, case-insensitively.
        call(get("/api/v1/posts?q=GRID")).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Carbon footprint"));
        // % is matched literally, not as a wildcard.
        call(get("/api/v1/posts?q=%25")).andExpect(jsonPath("$.data.length()").value(0));

        call(get("/api/v1/posts?status=PUBLISHED")).andExpect(jsonPath("$.data.length()").value(1));
        call(get("/api/v1/posts?tag=" + tag)).andExpect(jsonPath("$.data[0].title").value("Rightsizing pods"));

        call(get("/api/v1/posts?sort=title&direction=asc&size=2"))
                .andExpect(jsonPath("$.data[0].title").value("Carbon footprint"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.totalPages").value(2));
        call(get("/api/v1/posts?sort=title&direction=asc&size=2&page=1"))
                .andExpect(jsonPath("$.data[0].title").value("Spot instances"));
        call(get("/api/v1/posts?page=2147483647"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        call(get("/api/v1/posts?sort=password")).andExpect(status().isBadRequest());
        call(get("/api/v1/posts?status=BOGUS")).andExpect(status().isBadRequest());
    }

    @Test
    void countsCoverEveryStatus() throws Exception {
        createPost("Draft one", "x");
        String id = createPost("Published one", "y").path("id").asText();
        call(post("/api/v1/posts/" + id + "/publish"));

        call(get("/api/v1/posts/counts"))
                .andExpect(jsonPath("$.data.ALL").value(2))
                .andExpect(jsonPath("$.data.DRAFT").value(1))
                .andExpect(jsonPath("$.data.PUBLISHED").value(1))
                .andExpect(jsonPath("$.data.ARCHIVED").value(0));
    }

    // ── Authors & tags interplay ───────────────────────────────────────────

    @Test
    void deletingAnAuthorKeepsPostsAndCloseUpTheByline() throws Exception {
        String a = createAuthor("Alice");
        String b = createAuthor("Bob");
        String c = createAuthor("Carol");
        Map<String, Object> body = postBody("Three authors", doc("text"));
        body.put("authorIds", List.of(a, b, c));
        String id = data(call(post("/api/v1/posts"), body)).path("id").asText();

        call(delete("/api/v1/authors/" + b)).andExpect(status().isNoContent());

        JsonNode post = data(call(get("/api/v1/posts/" + id)).andExpect(status().isOk()));
        assertThat(post.path("authors").findValuesAsText("name")).containsExactly("Alice", "Carol");
        call(get("/api/v1/authors")).andExpect(jsonPath("$.data[0].postCount").value(1));
    }

    @Test
    void deletingATagRemovesItFromPosts() throws Exception {
        String tag = createTag("Temporary");
        Map<String, Object> body = postBody("Tagged", doc("text"));
        body.put("tagIds", List.of(tag));
        String id = data(call(post("/api/v1/posts"), body)).path("id").asText();

        call(delete("/api/v1/tags/" + tag)).andExpect(status().isNoContent());
        call(get("/api/v1/posts/" + id)).andExpect(jsonPath("$.data.tags.length()").value(0));
    }
}
