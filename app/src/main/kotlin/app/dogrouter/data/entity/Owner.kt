package app.dogrouter.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A dog's owner — the billing/contact party. Dogs reference an owner by id;
 * the billing screen rolls up done walks into a running account per owner.
 *
 * Owners are not hard-deleted once they have any billed services (bookkeeping
 * must stay intact). A wrong/throwaway owner can be marked [isTest] instead:
 * their turnover is left out of the URSSAF export and their invoices are clearly
 * marked as tests.
 */
@Entity(tableName = "owners")
data class Owner(
    @PrimaryKey val id: String,
    val firstName: String,
    val lastName: String,
    // Billing address (may differ from a dog's pickup address).
    val billingAddress: String,
    val phone: String?,
    val email: String?,
    // An "employeur particulier": for these only monthly hour totals matter,
    // not invoices or a running money account (they pay the walker a wage).
    val isEmployer: Boolean = false,
    // A test/throwaway owner: excluded from the URSSAF turnover; their invoices
    // are watermarked TEST and use a separate number series.
    val isTest: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** Display name "First Last", trimmed. */
    val displayName: String get() = "$firstName $lastName".trim()
}
