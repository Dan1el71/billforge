package com.billforge.business.product.dto;
 
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 
import java.math.BigDecimal;
 
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class CreateProductRequest {
 
    @NotBlank(message = "Product name is required")
    @Size(max = 255)
    private String name;
 
    private String description;
 
    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.00", message = "Price must be zero or greater")
    private BigDecimal price;
 
    /**
     * Tax rate percentage, for example 18.00 for 18%.
     * Defaults to 0 if not provided (tax-exempt product).
     */
    @DecimalMin(value = "0.00", message = "Tax percentage must be zero or greater")
    private BigDecimal taxPercentage;
 
    /** Tax classification code for tax reporting. */
    @Size(max = 20)
    private String taxCode;
 
    /** Unit of measure: "hrs", "kg", "units", "license" etc. */
    @Size(max = 50)
    private String unit;
}