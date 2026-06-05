package com.novel2script.backend.source.dto;

import jakarta.validation.constraints.NotBlank;

public class SubmitSourceRequest {

    @NotBlank(message = "小说正文不能为空")
    private String content;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
