package com.liunian.account.controller;

import com.liunian.account.common.R;
import com.liunian.account.security.UserContext;
import com.liunian.account.service.FamilyMemberService;
import com.liunian.account.service.FileStorageService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 图片上传（新增记账页凭证图）。
 *
 * <p>调用方式：{@code POST /api/upload}，multipart/form-data，字段名 {@code file}，
 * 可选 {@code familyId}（传了则校验家庭成员身份）。返回的 {@code url} 直接放入账单 images 数组。</p>
 */
@RestController
public class FileController {

    private final FileStorageService storageService;
    private final FamilyMemberService memberService;

    public FileController(FileStorageService storageService, FamilyMemberService memberService) {
        this.storageService = storageService;
        this.memberService = memberService;
    }

    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam(required = false) Long familyId) {
        if (familyId != null) {
            memberService.requireMember(UserContext.get(), familyId);
        }
        return R.ok(storageService.store(file));
    }
}
