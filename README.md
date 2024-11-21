### Reactive application

### Services

#### 1. Public API

- Description: Api gateway layer
- Port: 4000

#### 2. User Services

- Description: Client can register, login, get info
- Port: 3000

#### 3. Ingestion Services

- Description: Kafka producer layer
- Port: 3002

#### 4. Activity Services

- Description: Kafka producer/consumer/api activity layer. Push event send an email
- Port: 3001

#### 5. Congrast Services
- Description: Kafka consumer and send an email
