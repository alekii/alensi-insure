package io.alensi.insure.product;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
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

    /**
     * Whether the condition holds for {@code facts} (fact path → {@code BigDecimal}, {@code String},
     * {@code Boolean} or {@code LocalDate}). Empty when it can't be decided: the fact is missing or
     * of a type the operator can't compare. Callers decide what an undecided rule means; quoting
     * treats it as failed, so a missing fact never silently passes eligibility.
     */
    public Optional<Boolean> test(Map<String, Object> facts) {
        Object fact = facts.get(attribute);
        if (fact == null) {
            return Optional.empty();
        }
        return switch (operator) {
            case EQ -> equal(fact, value);
            case NE -> equal(fact, value).map(equal -> !equal);
            case IN, NOT_IN -> {
                boolean decided = false;
                for (JsonNode candidate : value) {
                    Optional<Boolean> equal = equal(fact, candidate);
                    if (equal.orElse(false)) {
                        yield Optional.of(operator == Operator.IN);
                    }
                    decided |= equal.isPresent();
                }
                yield decided ? Optional.of(operator == Operator.NOT_IN) : Optional.empty();
            }
            case GT -> compare(fact, value).map(c -> c > 0);
            case GTE -> compare(fact, value).map(c -> c >= 0);
            case LT -> compare(fact, value).map(c -> c < 0);
            case LTE -> compare(fact, value).map(c -> c <= 0);
            case BETWEEN -> compare(fact, value.get(0)).flatMap(low -> compare(fact, value.get(1))
                    .map(high -> low >= 0 && high <= 0));
        };
    }

    private static Optional<Boolean> equal(Object fact, JsonNode node) {
        if (fact instanceof BigDecimal || fact instanceof LocalDate) {
            return compare(fact, node).map(c -> c == 0);
        }
        if (fact instanceof Boolean flag) {
            return node.isBoolean() ? Optional.of(flag == node.booleanValue()) : Optional.empty();
        }
        return node.isString() ? Optional.of(fact.toString().equals(node.asString())) : Optional.empty();
    }

    /** fact compared to node: negative, zero or positive; empty when they aren't comparable. */
    static Optional<Integer> compare(Object fact, JsonNode node) {
        if (fact instanceof BigDecimal number && node.isNumber()) {
            return Optional.of(number.compareTo(node.decimalValue()));
        }
        if (fact instanceof LocalDate date && node.isString() && date(node.asString()) != null) {
            return Optional.of(date.compareTo(date(node.asString())));
        }
        return Optional.empty();
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
