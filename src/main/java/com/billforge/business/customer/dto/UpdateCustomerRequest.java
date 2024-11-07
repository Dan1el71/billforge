package com.billforge.business.customer.dto;
 
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class UpdateCustomerRequest {
 
    @Size(max = 255)
    private String name;
 
    @Email(message = "Email must be valid")
    @Size(max = 255)
    private String email;
 
    @Size(max = 50)
    private String phone;
 
    private String address;
 
    @Size(max = 50, message = "Tax ID must not exceed 50 characters")
    private String taxId;
}