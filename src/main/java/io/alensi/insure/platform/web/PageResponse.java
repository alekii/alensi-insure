package io.alensi.insure.platform.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** A page in the API's shape: {@code content}, {@code page}, {@code size}, {@code totalElements}. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements());
    }
}
