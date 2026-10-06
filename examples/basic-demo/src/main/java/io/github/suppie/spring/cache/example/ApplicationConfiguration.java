package io.github.suppie.spring.cache.example;

import org.apache.fory.json.ForyJson;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

@Configuration
public class ApplicationConfiguration {
  static class ForyJsonSerializer implements RedisSerializer<Object> {
    private final ForyJson foryJson = ForyJson.builder().build();

    @Override
    public byte @NonNull [] serialize(Object value) throws SerializationException {
      if (value == null) {
        return new byte[0];
      }
      try {
        // 1. Serialize the payload to a JSON string first
        byte[] innerJson = foryJson.toJsonBytes(value);

        // 2. Wrap it with the class type metadata
        TypeEnvelope envelope = new TypeEnvelope(value.getClass().getName(), innerJson);
        return foryJson.toJsonBytes(envelope);
      } catch (Exception e) {
        throw new SerializationException(
            "Generic Fory JSON serialization failed: " + e.getMessage(), e);
      }
    }

    @Override
    public Object deserialize(byte[] bytes) throws SerializationException {
      if (bytes == null || bytes.length == 0) {
        return null;
      }
      try {
        // 1. Deserialize the envelope
        TypeEnvelope envelope = foryJson.fromJson(bytes, TypeEnvelope.class);

        // 2. Resolve the target class type safely
        Class<?> targetClass = Class.forName(envelope.getClassName());

        // 3. Convert the inner JSON string into the final object target type
        return foryJson.fromJson(envelope.getPayloadBytes(), targetClass);
      } catch (Exception e) {
        throw new SerializationException(
            "Generic Fory JSON deserialization failed: " + e.getMessage(), e);
      }
    }

    // Envelope structure utilizing a String block payload
    private static final class TypeEnvelope {
      private String className;
      private byte[] payloadBytes;

      @SuppressWarnings("unused") // Required for Fory instantiation
      public TypeEnvelope() {}

      public TypeEnvelope(String className, byte[] payloadBytes) {
        this.className = className;
        this.payloadBytes = payloadBytes;
      }

      public String getClassName() {
        return className;
      }

      public void setClassName(String className) {
        this.className = className;
      }

      public byte[] getPayloadBytes() {
        return payloadBytes;
      }

      public void setPayloadBytes(byte[] payloadJson) {
        this.payloadBytes = payloadJson;
      }
    }
  }

  @Bean
  RedisSerializer<@NonNull Object> redisSerializer() {
    return new ForyJsonSerializer();
  }
}
