//package org.simplepoint.data.redis.lock;
//
//import java.util.concurrent.Callable;
//import java.util.concurrent.TimeUnit;
//import org.redisson.api.RLock;
//import org.redisson.api.RedissonClient;
//import org.simplepoint.api.lock.DistributedLocking;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
///**
// * Distributed locking implementation using Redisson.
// * This implementation uses pessimistic locking, automatically locking resources
// * and ensuring thread safety in a distributed environment.
// */
//public class RedissonDistributedLocking implements DistributedLocking<RLock> {
//
//  private static final Logger logger = LoggerFactory.getLogger(RedissonDistributedLocking.class);
//  private final RedissonClient redisson;
//
//  /**
//   * Constructs a new RedissonDistributedLocking instance.
//   *
//   * @param redisson the RedissonClient instance used for distributed locking
//   */
//  public RedissonDistributedLocking(RedissonClient redisson) {
//    this.redisson = redisson;
//  }
//
//  /**
//   * Executes a callable task with a distributed lock.
//   * Attempts to acquire the lock within the specified wait time and
//   * holds it for the specified lease time if successful.
//   *
//   * @param key       the key for the lock
//   * @param runnable  the task to execute
//   * @param waitTime  the maximum time to wait to acquire the lock (in seconds)
//   * @param leaseTime the time to hold the lock after acquisition (in seconds)
//   * @param <R>       the return type of the task
//   * @return the result of the executed task
//   * @throws Exception if the lock cannot be acquired or the task execution fails
//   */
//  @Override
//  public <R> R executeWithLock(String key, Callable<R> runnable, long waitTime, long leaseTime)
//      throws Exception {
//    RLock lock = redisson.getLock(key);
//    try {
//      logger.info("Attempting to acquire lock: {}", key);
//      if (lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS)) {
//        logger.info("Lock {} acquired successfully", key);
//        return runnable.call();
//      } else {
//        logger.warn("Failed to acquire lock {}", key);
//        throw new RuntimeException("Failed to acquire lock! Key: " + key);
//      }
//    } finally {
//      if (lock.isHeldByCurrentThread()) {
//        try {
//          lock.unlock();
//          logger.info("Lock {} released successfully", key);
//        } catch (IllegalMonitorStateException e) {
//          logger.error("Lock already released, cannot unlock! Key: {}", key);
//        }
//      }
//    }
//  }
//
//  /**
//   * Checks if the lock associated with the given key is currently locked.
//   *
//   * @param key the key for the lock
//   * @return true if the lock is locked, false otherwise
//   */
//  @Override
//  public boolean isLocked(String key) {
//    return redisson.getLock(key).isLocked();
//  }
//
//  /**
//   * Forces the unlock of the lock associated with the given key.
//   *
//   * @param key the key for the lock
//   * @return true if the lock was successfully unlocked, false otherwise
//   */
//  @Override
//  public boolean forceUnlock(String key) {
//    return redisson.getLock(key).forceUnlock();
//  }
//
//  /**
//   * Retrieves the RLock object associated with the given key.
//   *
//   * @param key the key for the lock
//   * @return the RLock instance
//   */
//  @Override
//  public RLock getLock(String key) {
//    return redisson.getLock(key);
//  }
//}
