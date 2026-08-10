package com.noted.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
    @Data
    public class RegisterRequest {

        @NotBlank(message = "Họ tên không được để trống")
        @Size(min = 2, max = 100)
        private String fullName;

        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không hợp lệ")
        private String email;

        @NotBlank(message = "Mật khẩu không được để trống")
        @Size(min = 8, message = "Mật khẩu tối thiểu 8 ký tự")
        @Pattern(regexp = "^(?=.*[A-Z])(?=.*[0-9])(?=.*[@#$%]).+$",
                message = "Mật khẩu phải có chữ hoa, số và ký tự đặc biệt")
        private String password;
    }

