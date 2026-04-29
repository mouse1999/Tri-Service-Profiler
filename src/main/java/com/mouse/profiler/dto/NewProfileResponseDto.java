package com.mouse.profiler.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonPropertyOrder({ "status", "page", "limit", "total", "total_pages", "links", "data" })
public class NewProfileResponseDto<T> {

    private String status;
    private int page;
    private int limit;
    private long total;

    @JsonProperty("total_pages")
    private int totalPages;

    private Links links;
    private List<T> data;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Links {
        private String self;
        private String next;
        private String prev;
    }

    /**
     * Static factory method for paginated success responses.
     */
    public static <T> NewProfileResponseDto<T> success(List<T> data, int page, int limit, long total, String baseUri) {
        int totalPages = (int) Math.ceil((double) total / limit);

        return NewProfileResponseDto.<T>builder()
                .status("success")
                .page(page)
                .limit(limit)
                .total(total)
                .totalPages(totalPages)
                .links(buildLinks(baseUri, page, limit, totalPages))
                .data(data)
                .build();
    }

    private static Links buildLinks(String baseUri, int page, int limit, int totalPages) {
        return Links.builder()
                .self(String.format("%s?page=%d&limit=%d", baseUri, page, limit))
                .next(page < totalPages ? String.format("%s?page=%d&limit=%d", baseUri, page + 1, limit) : null)
                .prev(page > 1 ? String.format("%s?page=%d&limit=%d", baseUri, page - 1, limit) : null)
                .build();
    }
}