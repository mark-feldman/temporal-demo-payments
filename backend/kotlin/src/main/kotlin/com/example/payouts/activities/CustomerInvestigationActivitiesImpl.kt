package com.example.payouts.activities

import com.example.payouts.model.activity.CustomerLookupRequest
import com.example.payouts.model.activity.CustomerLookupResponse
import io.temporal.spring.boot.ActivityImpl
import org.springframework.stereotype.Component

/** Read-only, deterministic demo records. No live customer or fraud service is contacted. */
@Component
@ActivityImpl(taskQueues = ["payouts"])
class CustomerInvestigationActivitiesImpl : CustomerInvestigationActivities {
    override fun lookupCustomerProfile(request: CustomerLookupRequest): CustomerLookupResponse {
        val profile = when (request.customerId) {
            "cust-0001" -> "synthetic account established over 4 years ago; customer identity verified; 2 prior recipients; no recorded fraud flags"
            "cust-0002" -> "synthetic account established over 2 years ago; customer identity verified; 1 prior recipient; one unresolved account-takeover alert"
            else -> "synthetic account established over 2 years ago; customer identity verified; 1 prior recipient; no recorded fraud flags"
        }
        return CustomerLookupResponse(profile)
    }

    override fun lookupRecentPayouts(request: CustomerLookupRequest): CustomerLookupResponse {
        val payouts = if (request.customerId == "cust-0001") {
            "synthetic last three payouts: USD 220, USD 310, USD 260; all completed; current recipient identity unavailable in this demo"
        } else {
            "synthetic last three payouts: USD 700, USD 850, USD 900; all completed; current recipient identity unavailable in this demo"
        }
        return CustomerLookupResponse(payouts)
    }
}
