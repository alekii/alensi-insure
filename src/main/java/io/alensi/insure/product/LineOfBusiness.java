package io.alensi.insure.product;

import java.util.Set;

/** Lines of business and the incident types their coverages can respond to (data dictionary). */
public enum LineOfBusiness {

    MOTOR(Set.of("COLLISION", "THEFT", "FIRE", "WINDSCREEN", "THIRD_PARTY_INJURY", "THIRD_PARTY_PROPERTY", "FLOOD")),
    HEALTH(Set.of("INPATIENT", "OUTPATIENT", "MATERNITY", "DENTAL", "OPTICAL")),
    PROPERTY(Set.of("FIRE", "BURGLARY", "FLOOD", "STORM", "ACCIDENTAL_DAMAGE")),
    LIFE(Set.of("DEATH", "PERMANENT_DISABILITY", "CRITICAL_ILLNESS")),
    TRAVEL(Set.of("MEDICAL_EMERGENCY", "TRIP_CANCELLATION", "LOST_BAGGAGE", "TRAVEL_DELAY"));

    private final Set<String> incidentTypes;

    LineOfBusiness(Set<String> incidentTypes) {
        this.incidentTypes = incidentTypes;
    }

    public Set<String> incidentTypes() {
        return incidentTypes;
    }
}
