package com.mouse.profiler.dto;


import lombok.Data;

@Data
public class JobStatus {
    private String jobId;
    private String status; // processing, completed, failed
    private CsvIngestionResult result;
}
