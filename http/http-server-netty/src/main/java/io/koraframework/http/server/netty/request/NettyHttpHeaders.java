package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.header.AbstractHttpHeaders;
import io.koraframework.http.common.header.HttpHeaders;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@NullMarked
public final class NettyHttpHeaders extends AbstractHttpHeaders implements HttpHeaders {

    private final io.netty.handler.codec.http.HttpHeaders nettyHeaders;
    @Nullable
    private volatile Set<String> names;
    @Nullable
    private volatile List<Map.Entry<String, List<String>>> entries;

    public NettyHttpHeaders(io.netty.handler.codec.http.HttpHeaders nettyHeaders) {
        this.nettyHeaders = nettyHeaders;
    }

    @Nullable
    @Override
    public String getFirst(String headerName) {
        return nettyHeaders.get(headerName);
    }

    @Override
    @Nullable
    public List<String> getAll(String headerName) {
        List<String> all = nettyHeaders.getAll(headerName);
        if (all.isEmpty()) {
            return null;
        }
        return Collections.unmodifiableList(all);
    }

    @Override
    public boolean has(String headerName) {
        return nettyHeaders.contains(headerName);
    }

    @Override
    public int size() {
        return nettyHeaders.size();
    }

    @Override
    public boolean isEmpty() {
        return nettyHeaders.isEmpty();
    }

    @Override
    public Set<String> names() {
        var n = this.names;
        if (n != null) {
            return n;
        }

        var nettyNames = nettyHeaders.names();
        var computedNames = new LinkedHashSet<String>(nettyNames.size());
        for (CharSequence headerName : nettyNames) {
            computedNames.add(headerName.toString().toLowerCase(Locale.ROOT));
        }

        var immutableNames = Collections.unmodifiableSet(computedNames);
        this.names = immutableNames;
        return immutableNames;
    }

    @Override
    public Iterator<Map.Entry<String, List<String>>> iterator() {
        var e = this.entries;
        if (e != null) {
            return e.iterator();
        }

        var linkedMap = new LinkedHashMap<String, List<String>>(nettyHeaders.size());
        var nettyIterator = nettyHeaders.iteratorCharSequence();

        while (nettyIterator.hasNext()) {
            var entry = nettyIterator.next();
            var lowerKey = entry.getKey().toString().toLowerCase(Locale.ROOT);
            var value = entry.getValue().toString();

            var list = (ArrayList<String>) linkedMap.get(lowerKey);
            if (list == null) {
                list = new ArrayList<>(2);
                linkedMap.put(lowerKey, list);
            }
            list.add(value);
        }

        var computedEntries = new ArrayList<Map.Entry<String, List<String>>>(linkedMap.size());
        linkedMap.forEach((k, v) -> computedEntries.add(Map.entry(k, Collections.unmodifiableList(v))));

        var immutableEntries = Collections.unmodifiableList(computedEntries);
        this.entries = immutableEntries;
        return immutableEntries.iterator();
    }

    @Override
    public String toString() {
        return nettyHeaders.toString();
    }
}
