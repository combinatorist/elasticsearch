/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations.bucket.range;

import com.google.common.collect.Lists;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.text.StringText;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.*;
import org.elasticsearch.search.aggregations.bucket.BucketStreamContext;
import org.elasticsearch.search.aggregations.bucket.BucketStreams;
import org.elasticsearch.search.aggregations.support.format.ValueFormatter;
import org.elasticsearch.search.aggregations.support.format.ValueFormatterStreams;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class InternalRange<B extends InternalRange.Bucket> extends InternalMultiBucketAggregation implements Range {

    static final Factory FACTORY = new Factory();

    public final static Type TYPE = new Type("range");

    private final static AggregationStreams.Stream STREAM = new AggregationStreams.Stream() {
        @Override
        public InternalRange readResult(StreamInput in) throws IOException {
            InternalRange ranges = new InternalRange();
            ranges.readFrom(in);
            return ranges;
        }
    };

    private final static BucketStreams.Stream<Bucket> BUCKET_STREAM = new BucketStreams.Stream<Bucket>() {
        @Override
        public Bucket readResult(StreamInput in, BucketStreamContext context) throws IOException {
            Bucket buckets = new Bucket(context.keyed(), context.formatter());
            buckets.readFrom(in);
            return buckets;
        }

        @Override
        public BucketStreamContext getBucketStreamContext(Bucket bucket) {
            BucketStreamContext context = new BucketStreamContext();
            context.formatter(bucket.formatter);
            context.keyed(bucket.keyed);
            return context;
        }
    };

    public static void registerStream() {
        AggregationStreams.registerStream(STREAM, TYPE.stream());
        BucketStreams.registerStream(BUCKET_STREAM, TYPE.stream());
    }

    public static class Bucket extends InternalMultiBucketAggregation.InternalBucket implements Range.Bucket {

        protected transient final boolean keyed;
        protected transient final ValueFormatter formatter;
        private double from;
        private double to;
        private long docCount;
        InternalAggregations aggregations;
        private String key;

        public Bucket(boolean keyed, @Nullable ValueFormatter formatter) {
            this.keyed = keyed;
            this.formatter = formatter;
        }

        public Bucket(String key, double from, double to, long docCount, InternalAggregations aggregations, boolean keyed, @Nullable ValueFormatter formatter) {
            this(keyed, formatter);
            this.key = key != null ? key : generateKey(from, to, formatter);
            this.from = from;
            this.to = to;
            this.docCount = docCount;
            this.aggregations = aggregations;
        }

        public String getKey() {
            return key;
        }

        @Override
        public Text getKeyAsText() {
            return new StringText(getKey());
        }

        @Override
        public Number getFrom() {
            return from;
        }

        @Override
        public Number getTo() {
            return to;
        }

        @Override
        public long getDocCount() {
            return docCount;
        }

        @Override
        public Aggregations getAggregations() {
            return aggregations;
        }

        protected Factory<? extends Bucket, ?> getFactory() {
            return FACTORY;
        }

        Bucket reduce(List<Bucket> ranges, ReduceContext context) {
            long docCount = 0;
            List<InternalAggregations> aggregationsList = Lists.newArrayListWithCapacity(ranges.size());
            for (Bucket range : ranges) {
                docCount += range.docCount;
                aggregationsList.add(range.aggregations);
            }
            final InternalAggregations aggs = InternalAggregations.reduce(aggregationsList, context);
            return getFactory().createBucket(key, from, to, docCount, aggs, keyed, formatter);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            if (keyed) {
                builder.startObject(key);
            } else {
                builder.startObject();
                builder.field(CommonFields.KEY, key);
            }
            if (!Double.isInfinite(from)) {
                builder.field(CommonFields.FROM, from);
                if (formatter != null) {
                    builder.field(CommonFields.FROM_AS_STRING, formatter.format(from));
                }
            }
            if (!Double.isInfinite(to)) {
                builder.field(CommonFields.TO, to);
                if (formatter != null) {
                    builder.field(CommonFields.TO_AS_STRING, formatter.format(to));
                }
            }
            builder.field(CommonFields.DOC_COUNT, docCount);
            aggregations.toXContentInternal(builder, params);
            builder.endObject();
            return builder;
        }

        protected String generateKey(double from, double to, @Nullable ValueFormatter formatter) {
            StringBuilder sb = new StringBuilder();
            sb.append(Double.isInfinite(from) ? "*" : formatter != null ? formatter.format(from) : ValueFormatter.RAW.format(from));
            sb.append("-");
            sb.append(Double.isInfinite(to) ? "*" : formatter != null ? formatter.format(to) : ValueFormatter.RAW.format(to));
            return sb.toString();
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {

        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {

        }
    }

    public static class Factory<B extends Bucket, R extends InternalRange<B>> {

        public String type() {
            return TYPE.name();
        }

        public R create(String name, List<B> ranges, @Nullable ValueFormatter formatter, boolean keyed, Map<String, Object> metaData) {
            return (R) new InternalRange<>(name, ranges, formatter, keyed, metaData);
        }


        public B createBucket(String key, double from, double to, long docCount, InternalAggregations aggregations, boolean keyed, @Nullable ValueFormatter formatter) {
            return (B) new Bucket(key, from, to, docCount, aggregations, keyed, formatter);
        }
    }

    private List<B> ranges;
    private Map<String, B> rangeMap;
    private @Nullable ValueFormatter formatter;
    private boolean keyed;

    public InternalRange() {} // for serialization

    public InternalRange(String name, List<B> ranges, @Nullable ValueFormatter formatter, boolean keyed, Map<String, Object> metaData) {
        super(name, metaData);
        this.ranges = ranges;
        this.formatter = formatter;
        this.keyed = keyed;
    }

    @Override
    public Type type() {
        return TYPE;
    }

    @Override
    public List<B> getBuckets() {
        return ranges;
    }

    @Override
    public B getBucketByKey(String key) {
        if (rangeMap == null) {
            rangeMap = new HashMap<>(ranges.size());
            for (Range.Bucket bucket : ranges) {
                rangeMap.put(bucket.getKey(), (B) bucket);
            }
        }
        return rangeMap.get(key);
    }

    protected Factory<B, ?> getFactory() {
        return FACTORY;
    }

    @Override
    public InternalAggregation reduce(ReduceContext reduceContext) {
        List<InternalAggregation> aggregations = reduceContext.aggregations();
        @SuppressWarnings("unchecked")
        List<Bucket>[] rangeList = new List[ranges.size()];
        for (int i = 0; i < rangeList.length; ++i) {
            rangeList[i] = new ArrayList<Bucket>();
        }
        for (InternalAggregation aggregation : aggregations) {
            InternalRange<?> ranges = (InternalRange<?>) aggregation;
            int i = 0;
            for (Bucket range : ranges.ranges) {
                rangeList[i++].add(range);
            }
        }

        final List<B> ranges = new ArrayList<>();
        for (int i = 0; i < this.ranges.size(); ++i) {
            ranges.add((B) rangeList[i].get(0).reduce(rangeList[i], reduceContext));
        }
        return getFactory().create(name, ranges, formatter, keyed, getMetaData());
    }

    @Override
    protected void doReadFrom(StreamInput in) throws IOException {
        formatter = ValueFormatterStreams.readOptional(in);
        keyed = in.readBoolean();
        int size = in.readVInt();
        List<B> ranges = Lists.newArrayListWithCapacity(size);
        for (int i = 0; i < size; i++) {
            String key = in.readOptionalString();
            ranges.add(getFactory().createBucket(key, in.readDouble(), in.readDouble(), in.readVLong(), InternalAggregations.readAggregations(in), keyed, formatter));
        }
        this.ranges = ranges;
        this.rangeMap = null;
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        ValueFormatterStreams.writeOptional(formatter, out);
        out.writeBoolean(keyed);
        out.writeVInt(ranges.size());
        for (B bucket : ranges) {
            out.writeOptionalString(((Bucket) bucket).key);
            out.writeDouble(((Bucket) bucket).from);
            out.writeDouble(((Bucket) bucket).to);
            out.writeVLong(((Bucket) bucket).docCount);
            bucket.aggregations.writeTo(out);
        }
    }

    @Override
    public XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        if (keyed) {
            builder.startObject(CommonFields.BUCKETS);
        } else {
            builder.startArray(CommonFields.BUCKETS);
        }
        for (B range : ranges) {
            range.toXContent(builder, params);
        }
        if (keyed) {
            builder.endObject();
        } else {
            builder.endArray();
        }
        return builder;
    }

}
