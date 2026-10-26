package io.atomity.blogcms.post;

import io.atomity.blogcms.post.dto.PostListQuery;
import io.atomity.blogcms.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostListQueryTest {

    @Test
    void defaultsToNewestUpdatedFirst() {
        Pageable pageable = new PostListQuery(null, null, null, null, null, null, null, null).toPageable();

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(10);
        Sort.Order updatedAt = pageable.getSort().getOrderFor("updatedAt");
        assertThat(updatedAt).isNotNull();
        assertThat(updatedAt.getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getSort().getOrderFor("id")).isNotNull();
    }

    @Test
    void clampsPageSizeToOneToHundred() {
        assertThat(new PostListQuery(null, null, null, null, null, null, null, 1000).toPageable().getPageSize()).isEqualTo(100);
        assertThat(new PostListQuery(null, null, null, null, null, null, null, 0).toPageable().getPageSize()).isEqualTo(1);
        assertThat(new PostListQuery(null, null, null, null, null, null, null, -5).toPageable().getPageSize()).isEqualTo(1);
    }

    @Test
    void negativePageBecomesFirstPage() {
        assertThat(new PostListQuery(null, null, null, null, null, null, -3, null).toPageable().getPageNumber()).isZero();
    }

    @Test
    void clampsExcessivelyLargePage() {
        int maxPage = Integer.MAX_VALUE / 100;
        assertThat(new PostListQuery(null, null, null, null, null, null, Integer.MAX_VALUE, null).toPageable().getPageNumber())
                .isEqualTo(maxPage);
        assertThat(new PostListQuery(null, null, null, null, null, null, 1_000_000_000, null).toPageable().getPageNumber())
                .isEqualTo(maxPage);
    }

    @Test
    void acceptsWhitelistedSortAndDirection() {
        Pageable pageable = new PostListQuery(null, null, null, null, "title", "asc", 1, 5).toPageable();

        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort().getOrderFor("title").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void rejectsUnknownSortAndDirection() {
        assertThatThrownBy(() -> new PostListQuery(null, null, null, null, "password", null, null, null).toPageable())
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo("BAD_REQUEST");
        assertThatThrownBy(() -> new PostListQuery(null, null, null, null, null, "sideways", null, null).toPageable())
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo("BAD_REQUEST");
    }
}
