package io.atomity.blogcms.blog;

import io.atomity.blogcms.blog.controller.BlogController;
import io.atomity.blogcms.blog.dto.BlogListQuery;
import io.atomity.blogcms.blog.service.BlogService;
import io.atomity.blogcms.post.controller.PostController;
import io.atomity.blogcms.post.dto.PostListQuery;
import io.atomity.blogcms.post.service.PostService;
import io.atomity.blogcms.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaginationVerificationTest {

    @Mock
    private BlogService blogService;

    @Mock
    private PostService postService;

    @InjectMocks
    private BlogController blogController;

    @InjectMocks
    private PostController postController;

    private MockMvc blogMockMvc;
    private MockMvc postMockMvc;

    @BeforeEach
    void setUp() {
        blogMockMvc = MockMvcBuilders.standaloneSetup(blogController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        postMockMvc = MockMvcBuilders.standaloneSetup(postController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("1 & 2. Normal Pagination on /blogs and /posts")
    class NormalPagination {

        @Test
        void normalPagesOnBlogsReturn200() throws Exception {
            for (int page : List.of(0, 1, 2)) {
                int finalPage = page;
                when(blogService.list(any())).thenAnswer(inv -> {
                    BlogListQuery q = inv.getArgument(0);
                    Pageable pageable = q != null ? q.toPageable() : org.springframework.data.domain.PageRequest.of(finalPage, 10);
                    return new PageImpl<>(List.of(), pageable, 30);
                });

                blogMockMvc.perform(get("/api/v1/blogs?page=" + page))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.meta.page").value(page))
                        .andExpect(jsonPath("$.meta.totalElements").value(30));
            }
        }

        @Test
        void normalPagesOnPostsReturn200() throws Exception {
            for (int page : List.of(0, 1, 2)) {
                int finalPage = page;
                when(postService.list(any())).thenAnswer(inv -> {
                    PostListQuery q = inv.getArgument(0);
                    Pageable pageable = q != null ? q.toPageable() : org.springframework.data.domain.PageRequest.of(finalPage, 10);
                    return new PageImpl<>(List.of(), pageable, 30);
                });

                postMockMvc.perform(get("/api/v1/posts?page=" + page))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.meta.page").value(page))
                        .andExpect(jsonPath("$.meta.totalElements").value(30));
            }
        }
    }

    @Nested
    @DisplayName("3 & 5. Extremely Large Page Values")
    class LargePageValues {

        @Test
        void largePageOnBlogsDoesNotOverflowOffsetAndReturns200() throws Exception {
            ArgumentCaptor<BlogListQuery> captor = ArgumentCaptor.forClass(BlogListQuery.class);
            when(blogService.list(captor.capture())).thenAnswer(inv -> {
                BlogListQuery q = inv.getArgument(0);
                Pageable pageable = q.toPageable();
                // Simulating JPA setFirstResult behavior
                long offset = pageable.getOffset();
                int firstResult = (int) offset;
                if (firstResult < 0) {
                    throw new InvalidDataAccessApiUsageException("firstResult cannot be negative: " + firstResult);
                }
                return new PageImpl<>(List.of(), pageable, 5);
            });

            // Integer.MAX_VALUE: 2147483647
            blogMockMvc.perform(get("/api/v1/blogs?page=2147483647"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0))
                    .andExpect(jsonPath("$.meta.page").value(Integer.MAX_VALUE / 100));

            // Verify offset is non-negative and within Integer range
            Pageable p1 = captor.getValue().toPageable();
            assertThat(p1.getOffset()).isPositive();
            assertThat((int) p1.getOffset()).isPositive();
            assertThat(p1.getOffset()).isLessThanOrEqualTo(Integer.MAX_VALUE);

            // 1 Billion: 1000000000
            blogMockMvc.perform(get("/api/v1/blogs?page=1000000000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0))
                    .andExpect(jsonPath("$.meta.page").value(Integer.MAX_VALUE / 100));
        }

        @Test
        void largePageOnPostsDoesNotOverflowOffsetAndReturns200() throws Exception {
            ArgumentCaptor<PostListQuery> captor = ArgumentCaptor.forClass(PostListQuery.class);
            when(postService.list(captor.capture())).thenAnswer(inv -> {
                PostListQuery q = inv.getArgument(0);
                Pageable pageable = q.toPageable();
                long offset = pageable.getOffset();
                int firstResult = (int) offset;
                if (firstResult < 0) {
                    throw new InvalidDataAccessApiUsageException("firstResult cannot be negative: " + firstResult);
                }
                return new PageImpl<>(List.of(), pageable, 5);
            });

            postMockMvc.perform(get("/api/v1/posts?page=2147483647"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0));

            Pageable p = captor.getValue().toPageable();
            assertThat(p.getOffset()).isPositive();
            assertThat((int) p.getOffset()).isPositive();
            assertThat(p.getOffset()).isLessThanOrEqualTo(Integer.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("4 & 6. Invalid Inputs Handling")
    class InvalidInputs {

        @Test
        void negativePageClampsToZero() throws Exception {
            when(blogService.list(any())).thenAnswer(inv -> {
                BlogListQuery q = inv.getArgument(0);
                return new PageImpl<>(List.of(), q.toPageable(), 10);
            });

            blogMockMvc.perform(get("/api/v1/blogs?page=-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.page").value(0));

            blogMockMvc.perform(get("/api/v1/blogs?page=-50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.page").value(0));
        }

        @Test
        void nonNumericPageReturns400() throws Exception {
            blogMockMvc.perform(get("/api/v1/blogs?page=abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].errorCode").value("VALIDATION_ERROR"));

            postMockMvc.perform(get("/api/v1/posts?page=xyz"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].errorCode").value("VALIDATION_ERROR"));
        }

        @Test
        void stringIntegerOverflowReturns400() throws Exception {
            // Numbers exceeding Java Long/Integer limits
            blogMockMvc.perform(get("/api/v1/blogs?page=2147483648"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].errorCode").value("VALIDATION_ERROR"));

            blogMockMvc.perform(get("/api/v1/blogs?page=99999999999999999999"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].errorCode").value("VALIDATION_ERROR"));

            postMockMvc.perform(get("/api/v1/posts?page=99999999999999999999"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].errorCode").value("VALIDATION_ERROR"));
        }
    }

    @Nested
    @DisplayName("9. Global Exception Handler Verification")
    class ExceptionHandlerBehavior {

        @Test
        void handlesInvalidDataAccessAsBadRequest() throws Exception {
            when(blogService.list(any())).thenThrow(
                    new InvalidDataAccessApiUsageException("Database usage error",
                            new IllegalArgumentException("Invalid argument")));

            blogMockMvc.perform(get("/api/v1/blogs?page=1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].errorCode").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.errors[0].message").value("Invalid argument"));
        }

        @Test
        void unrelatedServerErrorsStillReturn500() throws Exception {
            // Verify that genuine unexpected server errors are NOT hidden or masked as 400
            when(blogService.list(any())).thenThrow(new NullPointerException("Unexpected null reference"));

            blogMockMvc.perform(get("/api/v1/blogs?page=1"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.errors[0].errorCode").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.errors[0].message").value("An unexpected error occurred"));
        }
    }
}
