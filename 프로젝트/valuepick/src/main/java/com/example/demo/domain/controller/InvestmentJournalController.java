package com.example.demo.domain.controller;

import com.example.demo.domain.dto.*;
import com.example.demo.domain.dto.request.*;
import com.example.demo.domain.service.InvestmentJournalService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/journal")
public class InvestmentJournalController {

    private final InvestmentJournalService journalService;

    @GetMapping
    public ResponseEntity<JournalPageResult> getList(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "all") String category,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(
                journalService.getList(userDetails.getUsername(), category, q, from, to, page));
    }

    @GetMapping("/position/{journalId}")
    public ResponseEntity<InvestmentJournalDetailDto> getDetail(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long journalId) {
        return ResponseEntity.ok(
                journalService.getDetail(userDetails.getUsername(), journalId));
    }

    @PostMapping
    public ResponseEntity<Void> createJournal(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody CreateJournalRequest req) {
        journalService.createJournal(userDetails.getUsername(), req);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{journalId}/buy")
    public ResponseEntity<Void> addBuy(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long journalId,
            @RequestBody AddBuyRequest req) {
        journalService.addBuy(userDetails.getUsername(), journalId, req);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{journalId}/sell")
    public ResponseEntity<Void> addSell(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long journalId,
            @RequestBody AddSellRequest req) {
        journalService.addSell(userDetails.getUsername(), journalId, req);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{type}/{id}/title")
    public ResponseEntity<Void> updateTitle(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String type,
            @PathVariable Long id,
            @RequestBody UpdateTitleRequest req) {
        journalService.updateTitle(userDetails.getUsername(), type, id, req);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{type}/{id}/share")
    public ResponseEntity<Void> toggleShare(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String type,
            @PathVariable Long id) {
        journalService.toggleShare(userDetails.getUsername(), type, id);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/position/{journalId}/note")
    public ResponseEntity<Void> updateNote(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long journalId,
            @RequestBody UpdateNoteRequest req) {
        journalService.updateNote(userDetails.getUsername(), journalId, req);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{type}/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String type,
            @PathVariable Long id) {
        journalService.delete(userDetails.getUsername(), type, id);
        return ResponseEntity.ok().build();
    }
}
