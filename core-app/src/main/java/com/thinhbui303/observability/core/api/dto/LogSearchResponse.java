package com.thinhbui303.observability.core.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Paginated response for log search results")
public class LogSearchResponse<T> {
    @Schema(description = "List of matching log events")
    private List<T> content;
    private Integer page;
    private Integer size;
    @Schema(description = "Total number of elements matching the query", example = "1500")
    private Long totalElements;
    private Integer totalPages;
    @Schema(description = "Cursor value for deep pagination. Pass this as `search_after` in the next request.", example = "1694167530000,12345")
    private String nextSearchAfter;

    public LogSearchResponse() {
    }

    public List<T> getContent() { return content; }
    public void setContent(List<T> content) { this.content = content; }
    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }
    public Integer getSize() { return size; }
    public void setSize(Integer size) { this.size = size; }
    public Long getTotalElements() { return totalElements; }
    public void setTotalElements(Long totalElements) { this.totalElements = totalElements; }
    public Integer getTotalPages() { return totalPages; }
    public void setTotalPages(Integer totalPages) { this.totalPages = totalPages; }
    public String getNextSearchAfter() { return nextSearchAfter; }
    public void setNextSearchAfter(String nextSearchAfter) { this.nextSearchAfter = nextSearchAfter; }
}
