package io.github.bluuewhale.hashsmith;

import org.apache.commons.collections4.set.AbstractSetTest;
import org.junit.jupiter.api.Disabled;

import java.util.Set;

final class ApacheSwissSetTest<E> extends AbstractSetTest<E> {
    @Override public Set<E> makeObject() {
        return new SwissSet<>();
    }
}
