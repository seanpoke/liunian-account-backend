package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.security.UserContext;
import com.liunian.account.service.CategoryService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    public record CreateReq(Long familyId,
                            @NotBlank String name,
                            @NotBlank String type,
                            String code,
                            String icon,
                            String color) {
    }

    @GetMapping("/categories")
    public R<List<Map<String, Object>>> list(@RequestParam Long familyId,
                                             @RequestParam(required = false) String type) {
        return R.ok(categoryService.list(UserContext.get(), familyId, type));
    }

    @PostMapping("/category")
    public R<Map<String, Object>> create(@RequestBody CreateReq req) {
        return R.ok(categoryService.create(UserContext.get(), req.familyId(), req.name(), req.type(), req.code(), req.icon(), req.color()));
    }

    @DeleteMapping("/category/{id}")
    public R<Void> delete(@PathVariable Long id) {
        categoryService.delete(UserContext.get(), id);
        return R.ok();
    }
}
