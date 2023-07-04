# broke
## api入口
kafka.server.KafkaApis
## 事务
- kafka.coordinator.transaction.TransactionStateManager
- 当选举新的leader后载入事务文件

# produce
## 事务
- org.apache.kafka.clients.producer.internals.TransactionManager

- 请求及响应处理：org.apache.kafka.clients.producer.internals.TransactionManager.TxnRequestHandler

# consumer
