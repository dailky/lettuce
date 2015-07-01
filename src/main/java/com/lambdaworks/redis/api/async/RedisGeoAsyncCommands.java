package com.lambdaworks.redis.api.async;

import com.lambdaworks.redis.GeoArgs;
import com.lambdaworks.redis.GeoTuple;
import java.util.List;
import java.util.Set;
import com.lambdaworks.redis.RedisFuture;

/**
 * Asynchronous executed commands for the Geo-API.
 * 
 * @author <a href="mailto:mpaluch@paluch.biz">Mark Paluch</a>
 * @since 3.3
 * @generated by com.lambdaworks.apigenerator.CreateAsyncApi
 */
public interface RedisGeoAsyncCommands<K, V> {

    /**
     * Single geo add.
     * 
     * @param key
     * @param longitude
     * @param latitude
     * @param member
     * @return Long integer-reply the number of elements that were added to the set
     */
    RedisFuture<Long> geoadd(K key, double longitude, double latitude, V member);

    /**
     * Multi geo add.
     * 
     * @param key
     * @param lngLatMember triplets of double longitude, double latitude and V member
     * @return Long integer-reply the number of elements that were added to the set
     */
    RedisFuture<Long> geoadd(K key, Object... lngLatMember);

    /**
     * Retrieve members selected by distance with the center of {@code longitude} and {@code latitude}.
     * 
     * @param key
     * @param longitude
     * @param latitude
     * @param distance
     * @param unit
     * @return bulk reply
     */
    RedisFuture<Set<V>> georadius(K key, double longitude, double latitude, double distance, GeoArgs.Unit unit);

    /**
     * Retrieve members selected by distance with the center of {@code longitude} and {@code latitude}.
     * 
     * @param key
     * @param longitude
     * @param latitude
     * @param distance
     * @param unit
     * @return nested multi-bulk reply
     */
    RedisFuture<List<Object>> georadius(K key, double longitude, double latitude, double distance, GeoArgs.Unit unit,
            GeoArgs geoArgs);

    /**
     * Retrieve members selected by distance with the center of {@code member}.
     * 
     * @param key
     * @param member
     * @param distance
     * @param unit
     * @return bulk reply
     */
    RedisFuture<Set<V>> georadiusbymember(K key, V member, double distance, GeoArgs.Unit unit);

    /**
     *
     * Retrieve members selected by distance with the center of {@code member}.
     * 
     * @param key
     * @param member
     * @param distance
     * @param unit
     * @return nested multi-bulk reply
     */
    RedisFuture<List<Object>> georadiusbymember(K key, V member, double distance, GeoArgs.Unit unit, GeoArgs geoArgs);

    /**
     * Get geo coordinates for the {@code members}.
     *
     * @param key
     * @param members
     *
     * @return a list of {@link GeoTuple}s representing the x,y position of each element specified in the arguments. For missing
     *         elements {@literal null} is returned.
     */
    RedisFuture<List<GeoTuple>> geopos(K key, V... members);

    /**
     *
     * Retrieve distance between points {@code from} and {@code to}. If one or more elements are missing {@literal null} is
     * returned. Default in meters by, otherwise according to {@code unit}
     *
     * @param key
     * @param from
     * @param to
     *
     * @return distance between points {@code from} and {@code to}. If one or more elements are missing {@literal null} is
     *         returned.
     */
    RedisFuture<Double> geodist(K key, V from, V to, GeoArgs.Unit unit);

    /**
     *
     * Encode {@code longitude} and {@code latitude} to highest geohash accuracy.
     *
     * @param longitude
     * @param latitude
     * @return multi-bulk reply with 4 elements 1: the 52-bit geohash integer for your latitude longitude, 2: The minimum corner
     *         of your geohash {@link GeoTuple}, 3: The maximum corner of your geohash {@link GeoTuple}, 4: The averaged center
     *         of your geohash {@link GeoTuple}.
     */
    RedisFuture<List<Object>> geoencode(double longitude, double latitude);

    /**
     *
     * Encode {@code longitude} and {@code latitude} to highest geohash accuracy.
     *
     * @param longitude
     * @param latitude
     * @param distance
     * @param unit
     * @return multi-bulk reply with four components 1: the 52-bit geohash integer for your latitude longitude, 2: The minimum
     *         corner of your geohash {@link GeoTuple}, 3: The maximum corner of your geohash {@link GeoTuple}, 4: The averaged
     *         center of your geohash {@link GeoTuple}.
     */
    RedisFuture<List<Object>> geoencode(double longitude, double latitude, double distance, GeoArgs.Unit unit);

    /**
     *
     * Decode geohash.
     *
     * @param geohash
     * @return a list of {@link GeoTuple}s (nested multi-bulk) with 3 elements 1: minimum decoded corner, 2: maximum decoded
     *         corner, 3: averaged center of bounding box.
     */
    RedisFuture<List<GeoTuple>> geodecode(long geohash);
}