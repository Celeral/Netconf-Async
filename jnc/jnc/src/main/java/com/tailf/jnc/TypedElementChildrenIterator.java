package com.tailf.jnc;


import java.util.Iterator;

public class TypedElementChildrenIterator<T extends Element> implements Iterator<Element> {

    private Iterator<Element> childrenIterator;
    private Element nextChild;
    private boolean hasNextChild = false;
    private final String name;

    /**
     * Constructor to create new children iterator for all children.
     */
    public TypedElementChildrenIterator(NodeSet children) {
        if (children != null) {
            childrenIterator = children.iterator();
        } else {
            childrenIterator = null;
        }
        name = null;
    }

    /**
     * Constructor to create a new children iterator for children of a specific
     * name.
     */
    public TypedElementChildrenIterator(NodeSet children, String name) {
        if (children != null) {
            childrenIterator = children.iterator();
        } else {
            childrenIterator = null;
        }
        this.name = name;
    }

    /**
     * @return <code>true</code> if there are more children;
     *         <code>false</code> otherwise.
     */
    @Override
    public boolean hasNext() {
        if (hasNextChild) {
            return true;
        }
        if (childrenIterator == null) {
            return false;
        }
        while (childrenIterator.hasNext()) {
            if (name == null) {
                return true;
            }
            final Element child = childrenIterator.next();
            if (child.name.equals(name)) {
                hasNextChild = true;
                nextChild = child;
                return true;
            }
        }
        hasNextChild = false;
        return false;
    }

    /**
     * Iterates the Node set.
     * 
     * @return next element with this.name in set or null if none.
     */
    public T nextElement() {
        if (hasNextChild) {
            hasNextChild = false;
            return (T) nextChild;
        }
        while (childrenIterator.hasNext()) {
            final T child = (T)childrenIterator.next();
            if (name == null) {
                return child;
            } else if (child.name.equals(name)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Iterates the Node set.
     * 
     * @return next element with this.name in set or null if none.
     */
    @Override
    public T next() {
        return nextElement();
    }

    /**
     * Remove is not supported.
     */
    @Override
    public void remove() {
    }
}
