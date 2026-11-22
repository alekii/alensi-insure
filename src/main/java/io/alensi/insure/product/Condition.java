package io.alensi.insure.product;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

import io.alensi.insure.platform.web.Violations;
import tools.jackson.databind.JsonNode;

/**
 * A deterministic rule over facts, shared by eligibility rules (must hold, else DECLINE or REFER)
 * and structured exclusions (when it holds, the claim item isn't covered):
 *
 * <pre>{"attribute": "item.vehicle.age", "operator": "LTE", "value": 15}</pre>
 *
 * Attributes are fact paths under {@code customer}, {@code item}, {@code policy} and, for
 * exclusions, {@code claim}. Values: a scalar for EQ/NE; a number or ISO date for GT/GTE/LT/LTE;
 * a non-empty list for IN/NOT_IN; {@code [low, high]} for BETWEEN (inclusive).
 */
public record Condition(String attribute, Operator operator, JsonNode value) {

    public enum Operator { EQ, NE, GT, GTE, LT, LTE, IN, NOT_IN, BETWEEN }

    static final Set<String> QUOTE_FACTS = Set.of("customer", "item", "policy");
    static final Set<String> CLAIM_FACTS = Set.of("customer", "item", "policy", "claim");

    private static final Pattern PATH = Pattern.compile("^([a-z]+)(\\.[A-Za-z][A-Za-z0-9]*)+$");

    void validate(String field, Set<String> roots, Violations violations) {
        validatePath(attribute, field + ".attribute", roots, violations);
        if (operator == null) {
            violations.add(field + ".operator", "is required");
            return;
        }
        if (value == null || value.isNull()) {
            violations.add(field + ".value", "is required");
            return;
        }
        String problem = switch (operator) {
            case EQ, NE -> value.isValueNode() ? null : "must be a single value";
            case GT, GTE, LT, LTE -> comparable(value) ? null : "must be a number or an ISO date";
            case IN, NOT_IN -> value.isArray() && !value.isEmpty() && allScalars(value) ? null
                    : "must be a non-empty list of values";
            case BETWEEN -> value.isArray() && value.size() == 2 && comparable(value.get(0))
                    && comparable(value.get(1)) && inOrder(value.get(0), value.get(1)) ? null
                    : "must be [low, high] numbers or ISO dates, low ≤ high";
        };
        if (problem != null) {
            violations.add(field + ".value", problem);
        }
    }

    static void validatePath(String attribute, String field, Set<String> roots, Violations violations) {
        var path = attribute == null ? null : PATH.matcher(attribute);
        if (path == null || !path.matches() || !roots.contains(path.group(1))) {
            violations.add(field, "must be a fact path under " + roots + ", e.g. customer.age");
        }
    }

    private static boolean allScalars(JsonNode values) {
        for (JsonNode value : values) {
            if (!value.isValueNode() || value.isNull()) {
                return false;
            }
        }
        return true;
    }

    private static boolean comparable(JsonNode value) {
        return value.isNumber() || (value.isString() && date(value.asString()) != null);
    }

    private static boolean inOrder(JsonNode low, JsonNode high) {
        if (low.isNumber() && high.isNumber()) {
            return low.decimalValue().compareTo(high.decimalValue()) <= 0;
        }
        if (low.isString() && high.isString()) {
            return !date(low.asString()).isAfter(date(high.asString()));
        }
        return false;
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
