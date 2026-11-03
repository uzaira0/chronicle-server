package com.openlattice.chronicle.services.jobs

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.openlattice.chronicle.services.notifications.Notification

/**
 * @author Solomon Tang <solomon@openlattice.com>
 *
 * Jobs accept only their published type names; class-name aliases are not part of the contract.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = EmptyJobDefinition::class, name = "EmptyJobDefinition"),
    JsonSubTypes.Type(value = Notification::class, name = "Notification"),
)
public interface ChronicleJobDefinition
