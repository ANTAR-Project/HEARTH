package com.antar.cameraorchestrator.controller.api;

import com.antar.cameraorchestrator.model.entity.StateHistoryEntity;
import com.antar.cameraorchestrator.repository.StateHistoryRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/history")
public class HistoryApiController {

    private final StateHistoryRepository historyRepository;

    public HistoryApiController(StateHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    @GetMapping("/entity/{entityId}")
    public ResponseEntity<List<StateHistoryEntity>> getHistory(
            @PathVariable String entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {

        if (start != null && end != null) {
            return ResponseEntity.ok(historyRepository.findHistoryBetween(entityId, start, end));
        }
        if (since != null) {
            return ResponseEntity.ok(historyRepository.findHistorySince(entityId, since));
        }
        return ResponseEntity.ok(historyRepository.findByEntityIdOrderByTimestampDesc(entityId));
    }
}
