package io.atomity.blogcms.post.dto;

import io.atomity.blogcms.post.entity.PostStatus;
import io.atomity.blogcms.shared.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;
import java.util.UUID;

/** Query parameters of GET /api/v1/posts. */
public record PostListQuery(
        PostStatus status,
        String q,
        UUID tag,
        UUID author,
        String sort,
        String direction,
        Integer page,
        Integer size) {

    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 100;
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_SIZE;
    /** API sort keys → entity attributes. Anything else is rejected rather than passed to the database. */
    private static final Map<String, String> SORTABLE = Map.of(
            "updatedAt", "updatedAt",
            "title", "title",
            "publishedAt", "publishedAt",
            "wordCount", "wordCount");

    public Pageable toPageable() {
        String sortKey = sort == null ? "updatedAt" : sort;
        String attribute = SORTABLE.get(sortKey);
        if (attribute == null) {
            throw ApiException.badRequest("BAD_REQUEST", "sort must be one of " + String.join(", ", SORTABLE.keySet()));
        }
        Sort.Direction dir;
        if (direction == null) {
            dir = Sort.Direction.DESC;
        } else if (direction.equalsIgnoreCase("asc") || direction.equalsIgnoreCase("desc")) {
            dir = Sort.Direction.fromString(direction);
        } else {
            throw ApiException.badRequest("BAD_REQUEST", "direction must be asc or desc");
        }
        int pageNumber = page == null ? 0 : Math.min(MAX_PAGE, Math.max(0, page));
        int pageSize = size == null ? DEFAULT_SIZE : Math.min(MAX_SIZE, Math.max(1, size));
        // id breaks ties so pages never overlap. (Null placement for publishedAt is Postgres's
        // default — Spring Data can't express NULLS LAST through the Criteria API.)
        Sort order = Sort.by(new Sort.Order(dir, attribute), Sort.Order.asc("id"));
        return PageRequest.of(pageNumber, pageSize, order);
    }
}
