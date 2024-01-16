package io.siggi.databackup.util;

import java.util.Iterator;
import java.util.NoSuchElementException;

public abstract class ReadingIterator<T> implements Iterator<T> {
    private boolean calculated = false;
    private T next = null;
    protected abstract T read();

    @Override
    public boolean hasNext() {
        if (!calculated) {
            calculated = true;
            next = read();
        }
        return next != null;
    }

    @Override
    public T next() {
        if (!hasNext()) throw new NoSuchElementException();
        calculated = false;
        T value = next;
        next = null;
        return value;
    }
}
