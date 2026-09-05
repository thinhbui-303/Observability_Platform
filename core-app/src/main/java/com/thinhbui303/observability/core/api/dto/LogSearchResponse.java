package com.thinhbui303.observability.core.api.dto;

import java.util.List;

public class LogSearchResponse<T> {
    private List<T> content;
    private Integer page;
    private Integer size;
    private Long totalElements;
    private Integer totalPages;
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
