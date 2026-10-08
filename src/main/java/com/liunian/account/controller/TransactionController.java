package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.security.UserContext;
import com.liunian.account.service.TransactionService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    public record TxReq(@NotBlank String type,
                        Integer amount,
                        Long categoryId,
                        @NotBlank String date,
                        String note,
                        List<String> images) {
    }

    private LocalDate parse(String s) {
        return s == null ? null : LocalDate.parse(s);
    }

    @PostMapping("/transaction")
    public R<Map<String, Object>> create(@RequestBody TxReq req) {
        Long id = transactionService.create(UserContext.get(), req.type(), req.amount(),
                req.categoryId(), parse(req.date()), req.note(), req.images());
        return R.ok(Map.of("id", id));
    }

    @PutMapping("/transaction/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody TxReq req) {
        transactionService.update(UserContext.get(), id, req.type(), req.amount(),
                req.categoryId(), parse(req.date()), req.note(), req.images());
        return R.ok();
    }

    @DeleteMapping("/transaction/{id}")
    public R<Void> delete(@PathVariable Long id) {
        transactionService.delete(UserContext.get(), id);
        return R.ok();
    }

    @GetMapping("/transaction/{id}")
    public R<Map<String, Object>> get(@PathVariable Long id) {
        return R.ok(transactionService.get(UserContext.get(), id));
    }

    @GetMapping("/transactions")
    public R<Map<String, Object>> list(@RequestParam(required = false) String start,
                                       @RequestParam(required = false) String end,
                                       @RequestParam(required = false) String member,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(required = false) Long category,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(transactionService.list(UserContext.get(), parse(start), parse(end),
                member, type, category, page, size));
    }

    @GetMapping("/transactions/by-day")
    public R<Map<String, Object>> listByDay(@RequestParam(required = false) String start,
                                            @RequestParam(required = false) String end) {
        return R.ok(transactionService.listByDay(UserContext.get(), parse(start), parse(end)));
    }
}
