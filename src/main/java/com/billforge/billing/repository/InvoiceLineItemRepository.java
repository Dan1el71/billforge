package com.billforge.billing.repository;

import com.billforge.billing.entity.InvoiceLineItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvoiceLineItemRepository extends JpaRepository<InvoiceLineItem, Long> {

    List<InvoiceLineItem> findByInvoiceId(Long invoiceId);
}
