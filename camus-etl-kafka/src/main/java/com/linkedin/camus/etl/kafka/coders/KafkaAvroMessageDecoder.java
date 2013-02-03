package com.linkedin.camus.etl.kafka.coders;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Properties;

import kafka.message.Message;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData.Record;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;

import com.linkedin.camus.coders.CamusWrapper;
import com.linkedin.camus.coders.MessageDecoder;
import com.linkedin.camus.coders.MessageDecoderException;
import com.linkedin.camus.schemaregistry.CachedSchemaRegistry;
import com.linkedin.camus.schemaregistry.SchemaRegistry;

public class KafkaAvroMessageDecoder extends MessageDecoder<Message, Record> {
	protected DecoderFactory decoderFactory;
	protected SchemaRegistry<Schema> registry;
	private Schema latestSchema;
	
	@Override
	public void init(Properties props, String topicName) {
	    super.init(props, topicName);
	    try {
            SchemaRegistry<Schema> registry = (SchemaRegistry<Schema>) Class
                    .forName(
                            props.getProperty(KafkaAvroMessageEncoder.KAFKA_MESSAGE_CODER_SCHEMA_REGISTRY_CLASS)).newInstance();
            
            registry.init(props);
            
            this.registry = new CachedSchemaRegistry<Schema>(registry);
            this.latestSchema = registry.getLatestSchemaByTopic(topicName).getSchema();
        } catch (Exception e) {
            throw new MessageDecoderException(e);
        }

        decoderFactory = DecoderFactory.get();
	}

	private class MessageDecoderHelper {
		private Message message;
		private ByteBuffer buffer;
		private Schema schema;
		private int start;
		private int length;
		private Schema targetSchema;
		private static final byte MAGIC_BYTE = 0x0;
		private final SchemaRegistry<Schema> registry;
		private final String topicName;

		public MessageDecoderHelper(SchemaRegistry<Schema> registry,
				String topicName, Message message) {
			this.registry = registry;
			this.topicName = topicName;
			this.message = message;
		}

		public ByteBuffer getBuffer() {
			return buffer;
		}

		public Schema getSchema() {
			return schema;
		}

		public int getStart() {
			return start;
		}

		public int getLength() {
			return length;
		}

		public Schema getTargetSchema() {
			return targetSchema;
		}

		private ByteBuffer getByteBuffer(Message message) {
			ByteBuffer buffer = message.payload();
			if (buffer.get() != MAGIC_BYTE)
				throw new IllegalArgumentException("Unknown magic byte!");
			return buffer;
		}

		public MessageDecoderHelper invoke() {
			buffer = getByteBuffer(message);
			String id = Integer.toString(buffer.getInt());
			schema = registry.getSchemaByID(topicName, id);
			if (schema == null)
				throw new IllegalStateException("Unknown schema id: " + id);

			start = buffer.position() + buffer.arrayOffset();
			length = buffer.limit() - 5;

			// try to get a target schema, if any
			targetSchema = latestSchema;
			return this;
		}
	}

	public CamusWrapper<Record> decode(Message message) {
		try {
			MessageDecoderHelper helper = new MessageDecoderHelper(registry,
					topicName, message).invoke();
			DatumReader<Record> reader = (helper.getTargetSchema() == null) ? new GenericDatumReader<Record>(
					helper.getSchema()) : new GenericDatumReader<Record>(
					helper.getSchema(), helper.getTargetSchema());

			return new CamusAvroWrapper(reader.read(null, decoderFactory
                    .binaryDecoder(helper.getBuffer().array(),
                            helper.getStart(), helper.getLength(), null)));
	
		} catch (IOException e) {
			throw new MessageDecoderException(e);
		}
	}

	public static class CamusAvroWrapper extends CamusWrapper<Record> {

	    public CamusAvroWrapper(Record record) {
            super(record);
        }
	    
	    @Override
	    public long getTimestamp() {
	        Record header = (Record) super.getRecord().get("header");

	        if (header != null && header.get("time") != null) {
	            return (Long) header.get("time");
	        } else if (super.getRecord().get("timestamp") != null) {
	            return (Long) super.getRecord().get("timestamp");
	        } else {
	            return System.currentTimeMillis();
	        }
	    }

	    @Override
	    public String getServer() {
	        Record header = (Record) super.getRecord().get("header");
	        if (header != null && header.get("server") != null) {
	            return header.get("server").toString();
	        } else {
	            return "unknown_server";
	        }
	    }

	    @Override
	    public String getService() {
	        Record header = (Record) super.getRecord().get("header");
	        if (header != null && header.get("service") != null) {
	            return header.get("service").toString();
	        } else {
	            return "unknown_service";
	        }
	    }
	}

}