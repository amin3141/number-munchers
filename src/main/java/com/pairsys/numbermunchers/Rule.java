package com.pairsys.numbermunchers;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class Rule {
    private interface RuleMatcher {
        boolean test(int value);
    }

    private final String description;
    private final RuleMatcher matcher;
    private final int helper;

    private Rule(String description, RuleMatcher matcher, int helper) {
        this.description = description;
        this.matcher = matcher;
        this.helper = helper;
    }

    public static Rule randomRule(Random random, int round) {
        if (round == 1) {
            int multiple = 2 + random.nextInt(8);
            return new Rule("multiples of " + multiple, value -> value % multiple == 0, multiple);
        }

        int pick = random.nextInt(6);
        if (pick == 0) {
            return new Rule("even", value -> value % 2 == 0, -1);
        }
        if (pick == 1) {
            return new Rule("odd", value -> value % 2 != 0, -1);
        }
        if (pick == 2) {
            int multiple = 3 + random.nextInt(Math.min(7, 2 + round));
            return new Rule("multiples of " + multiple, value -> value % multiple == 0, multiple);
        }
        if (pick == 3) {
            int factorTarget = 24 + random.nextInt(80);
            return new Rule("factors of " + factorTarget, value -> factorTarget % value == 0, factorTarget);
        }
        if (pick == 4) {
            return new Rule("prime", Rule::isPrime, -1);
        }
        return new Rule("perfect squares", value -> {
            int root = (int) Math.round(Math.sqrt(value));
            return root * root == value;
        }, -1);
    }

    public boolean matches(int value) {
        return matcher.test(value);
    }

    public String getDescription() {
        return description;
    }

    public int generateMatching(Random random) {
        if (description.startsWith("multiples of ")) {
            int base = helper;
            int count = Math.max(2, 100 / base);
            return base * (1 + random.nextInt(count));
        }
        if (description.startsWith("factors of ")) {
            List<Integer> factors = new ArrayList<>();
            for (int i = 1; i <= helper; i++) {
                if (helper % i == 0 && i <= 99) {
                    factors.add(i);
                }
            }
            if (!factors.isEmpty()) {
                return factors.get(random.nextInt(factors.size()));
            }
        }

        int tries = 0;
        while (tries++ < 500) {
            int value = random.nextInt(99) + 1;
            if (matches(value)) {
                return value;
            }
        }
        return 2;
    }

    private static boolean isPrime(int value) {
        if (value < 2) {
            return false;
        }
        if (value == 2) {
            return true;
        }
        if (value % 2 == 0) {
            return false;
        }
        for (int i = 3; i * i <= value; i += 2) {
            if (value % i == 0) {
                return false;
            }
        }
        return true;
    }
}
