package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.result.Result;
import com.hmdp.constant.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 生成新文件名
            String fileName = createNewFileName(originalFilename);
            // 保存文件到目标目录(前端nginx静态资源服务器的目录下)
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        File file = new File(SystemConstants.IMAGE_UPLOAD_DIR, filename);
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }

    private String createNewFileName(String originalFilename) {
        //获取当前系统日期的字符串,格式为 yyyy/MM
        String dir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        //截取原始文件名的后缀   dfdfdf.png
        String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        //生成一个新的不重复的文件名
        String newFileName = UUID.randomUUID() + extension;
        String objectName = "/blogs/" + dir + "/" + newFileName;
        // 判断目录是否存在
        File file = new File(SystemConstants.IMAGE_UPLOAD_DIR + "/blogs/" + dir);
        if (!file.exists()) {
            file.mkdirs();
        }
        // 返回文件名
        return objectName;
    }


//    private String createNewFileName(String originalFilename) {
//        // 获取后缀
//        String suffix = StrUtil.subAfter(originalFilename, ".", true);
//        // 生成目录
//        String name = UUID.randomUUID().toString();
//        int hash = name.hashCode();
//        int d1 = hash & 0xF;
//        int d2 = (hash >> 4) & 0xF;
//        // 判断目录是否存在
//        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("/blogs/{}/{}", d1, d2));
//        if (!dir.exists()) {
//            dir.mkdirs();
//        }
//        // 生成文件名
//        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
//    }
}
