package com.billforge.business.customer.entity;

import com.billforge.shared.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * Tenant's end-customer - the person or business the tenant sends invoices to.
 *
 * NOT the same as auth.User (which is a team member of the tenant).
 *
 * Lives in the tenant schema - each tenant has its own isolated customer table.
 *
 * stripeCustomerId is created via PaymentGatewayPort.createCustomer() during
 * customer creation and stored here for future payment link generation.
 *
 * taxId (Tax identifier) is optional but required for tax-compliant
 * B2B invoices .
 */
@Entity
@Table(name = "customers")
@SQLDelete(sql = "UPDATE customers SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer extends AuditableEntity {

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String email;

    @Column(length = 50)
    private String phone;

    @Column(columnDefinition = "TEXT")
    private String address;

    /**
     * Optional tax identifier such as VAT, EIN, ABN or NIT.
     * Printed on invoices when present.
     */
    @Column(name = "tax_id", length = 50)
    private String taxId;

    /**
     * Stripe customer ID (cust_XXXX).
     * Created via PaymentGatewayPort.createCustomer() during customer creation.
     * Null until the customer has been synced to Stripe.
     */
    @Column(name = "stripe_customer_id", length = 100)
    private String stripeCustomerId;
}