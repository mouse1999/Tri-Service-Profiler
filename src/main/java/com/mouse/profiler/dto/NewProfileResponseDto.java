package com.mouse.profiler.dto;

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
@JsonPropertyOrder({ "status", "page", "limit", "total", "data" })
public class NewProfileResponseDto<T> {

    private String status;
    private int page;
    private int limit;
    private long total;
    private List<T> data;

    /**
     * Static factory method for success responses.
     */
    public static <T> NewProfileResponseDto<T> success(List<T> data, int page, int limit, long total) {
        return NewProfileResponseDto.<T>builder()
                .status("success")
                .page(page)
                .limit(limit)
                .total(total)
                .data(data)
                .build();
    }
}