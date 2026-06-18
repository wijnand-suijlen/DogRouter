package app.dogrouter.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Symmetric incompatibility between two dogs.
 *
 * Application code stores rows with dogIdA < dogIdB (alphabetical) to keep
 * the pair canonical and avoid double bookkeeping.
 */
@Entity(
    tableName = "dog_incompatibilities",
    primaryKeys = ["dogIdA", "dogIdB"],
    foreignKeys = [
        ForeignKey(
            entity = Dog::class,
            parentColumns = ["id"],
            childColumns = ["dogIdA"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Dog::class,
            parentColumns = ["id"],
            childColumns = ["dogIdB"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("dogIdA"),
        Index("dogIdB"),
    ],
)
data class DogIncompatibility(
    val dogIdA: String,
    val dogIdB: String,
)
