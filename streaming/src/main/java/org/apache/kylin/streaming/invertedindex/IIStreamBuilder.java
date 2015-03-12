/*
 *
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *
 *  contributor license agreements. See the NOTICE file distributed with
 *
 *  this work for additional information regarding copyright ownership.
 *
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *
 *  (the "License"); you may not use this file except in compliance with
 *
 *  the License. You may obtain a copy of the License at
 *
 *
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 *
 *  Unless required by applicable law or agreed to in writing, software
 *
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 *  See the License for the specific language governing permissions and
 *
 *  limitations under the License.
 *
 * /
 */

package org.apache.kylin.streaming.invertedindex;

import com.google.common.base.Function;
import com.google.common.base.Stopwatch;
import com.google.common.collect.*;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.kylin.dict.Dictionary;
import org.apache.kylin.dict.DictionaryGenerator;
import org.apache.kylin.invertedindex.index.Slice;
import org.apache.kylin.invertedindex.index.SliceBuilder;
import org.apache.kylin.invertedindex.index.TableRecord;
import org.apache.kylin.invertedindex.index.TableRecordInfo;
import org.apache.kylin.invertedindex.model.IIDesc;
import org.apache.kylin.invertedindex.model.IIKeyValueCodec;
import org.apache.kylin.invertedindex.model.IIRow;
import org.apache.kylin.metadata.measure.fixedlen.FixedLenMeasureCodec;
import org.apache.kylin.metadata.model.ColumnDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.streaming.Stream;
import org.apache.kylin.streaming.StreamBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Created by qianzhou on 3/3/15.
 */
public class IIStreamBuilder extends StreamBuilder {

    private static Logger logger = LoggerFactory.getLogger(IIStreamBuilder.class);

    private IIDesc desc = null;
    private HTableInterface hTable = null;
    private int partitionId = -1;

    public IIStreamBuilder(LinkedBlockingDeque<Stream> queue, String hTableName, IIDesc desc, int partitionId) {
        super(queue, desc.getSliceSize());
        this.desc = desc;
        this.partitionId = partitionId;
        try {
            this.hTable = HConnectionManager.createConnection(HBaseConfiguration.create()).getTable(hTableName);
        } catch (IOException e) {
            logger.error("cannot open htable name:" + hTableName, e);
            throw new RuntimeException("cannot open htable name:" + hTableName, e);
        }
    }

    @Override
    protected void build(List<Stream> streamsToBuild) throws IOException {
        logger.info("stream build start, size:" + streamsToBuild.size());
        Stopwatch stopwatch = new Stopwatch().start();
        List<List<String>> table = Lists.transform(streamsToBuild, new Function<Stream, List<String>>() {
            @Nullable
            @Override
            public List<String> apply(@Nullable Stream input) {
                return parseStream(input, desc);
            }
        });
        final Map<Integer, Dictionary<?>> dictionaryMap = buildDictionary(table, desc);
        Map<TblColRef, FixedLenMeasureCodec<?>> measureCodecMap = Maps.newHashMap();
        int index = 0;
        for (TblColRef tblColRef : desc.listAllColumns()) {
            ColumnDesc col = tblColRef.getColumn();
            if (desc.isMetricsCol(index++)) {
                measureCodecMap.put(tblColRef, FixedLenMeasureCodec.get(col.getType()));
            }
        }
        TableRecordInfo tableRecordInfo = new TableRecordInfo(desc, dictionaryMap, measureCodecMap);
        SliceBuilder sliceBuilder = new SliceBuilder(tableRecordInfo, (short) partitionId);
        final Slice slice = buildSlice(table, sliceBuilder, tableRecordInfo, dictionaryMap);
        loadToHBase(hTable, slice, new IIKeyValueCodec());
        submitOffset();
        stopwatch.stop();
        logger.info("stream build finished, size:" + streamsToBuild.size() + " elapsed time:" + stopwatch.elapsedTime(TimeUnit.MILLISECONDS) + TimeUnit.MILLISECONDS);
    }

    private Map<Integer, Dictionary<?>> buildDictionary(List<List<String>> table, IIDesc desc) {
        SetMultimap<TblColRef, String> valueMap = HashMultimap.create();
        Set<TblColRef> dimensionColumns = Sets.newHashSet();
        for (int i = 0; i < desc.listAllColumns().size(); i++) {
            if (!desc.isMetricsCol(i)) {
                dimensionColumns.add(desc.listAllColumns().get(i));
            }
        }
        for (List<String> row : table) {
            for (int i = 0; i < row.size(); i++) {
                String cell = row.get(i);
                valueMap.put(desc.listAllColumns().get(i), cell);
            }
        }
        Map<Integer, Dictionary<?>> result = Maps.newHashMap();
        for (TblColRef tblColRef : valueMap.keys()) {
            result.put(desc.findColumn(tblColRef), DictionaryGenerator.buildDictionaryFromValueList(Collections2.transform(valueMap.get(tblColRef), new Function<String, byte[]>() {
                @Nullable
                @Override
                public byte[] apply(String input) {
                    return input.getBytes();
                }
            }), tblColRef.getType()));
        }
        return result;
    }

    private List<String> parseStream(Stream stream, IIDesc desc) {
        return Lists.newArrayList(new String(stream.getRawData()).split(","));
    }

    private Slice buildSlice(List<List<String>> table, SliceBuilder sliceBuilder, TableRecordInfo tableRecordInfo, Map<Integer, Dictionary<?>> localDictionary) {
        for (List<String> row : table) {
            TableRecord tableRecord = tableRecordInfo.createTableRecord();
            for (int i = 0; i < row.size(); i++) {
                tableRecord.setValueString(i, row.get(i));
            }
            sliceBuilder.append(tableRecord);
        }
        final Slice slice = sliceBuilder.close();
        slice.setLocalDictionaries(localDictionary);
        return slice;
    }

    private byte[] getDictBytes(Dictionary<?> dict) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeUTF(dict.getClass().getName());
        dict.write(out);
        return baos.toByteArray();
    }

    private void loadToHBase(HTableInterface hTable, Slice slice, IIKeyValueCodec codec) throws IOException {
        try {
            List<Put> data = Lists.newArrayList();
            for (IIRow row : codec.encodeKeyValue(slice)) {
                final byte[] key = row.getKey().get();
                final byte[] value = row.getValue().get();
                Put put = new Put(key);
                put.add(IIDesc.HBASE_FAMILY_BYTES, IIDesc.HBASE_QUALIFIER_BYTES, value);
                final ImmutableBytesWritable dictionary = row.getDictionary();
                final byte[] dictBytes = dictionary.get();
                if (dictionary.getOffset() == 0 && dictionary.getLength() == dictBytes.length) {
                    put.add(IIDesc.HBASE_FAMILY_BYTES, IIDesc.HBASE_DICTIONARY_BYTES, dictBytes);
                } else {
                    throw new RuntimeException("dict offset should be 0, and dict length should be " + dictBytes.length + " but they are" + dictionary.getOffset() + " " + dictionary.getLength());
                }
                data.add(put);
            }
            hTable.put(data);
        } finally {
            hTable.close();
        }
    }


    private void submitOffset() {

    }

}
