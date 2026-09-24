# TARBANK

## High level description

Develop a service that simulates basic banking operations in a programming language of your choice. This service will manage accounts, process deposits, withdrawals, and transfers between accounts.

This test aims to assess your problem-solving skills by building a system that reflects the real-world constraints of a bank. It will also evaluate your ability to adhere to engineering best practices. Please keep this in mind and incorporate anything you consider relevant to both areas.

## Basic requirements

1. A class or set of functions that allow:
   - Account creation: Allow users to create an account with an initial deposit.
   - Deposit: Enable users to deposit money into their account.
   - Withdrawal: Allow users to withdraw money from their account, ensuring that overdrafts are not allowed.
   - Transfer: Enable transferring funds between accounts.
   - Account balance: Provide the ability to check the account balance.

2. Database:
   - In-memory data storage will suffice, no need to have a database alongside the project, but you can add one at your discretion

## Functional requirements

1. Service provides a REST API for:
   - JWT auth/auth as a basic security mechanism
        - Username and password with hashing and salting the password
        - Jwt token expires in configurable amount of minutes. By default it should be in 1 hour.
        - Password can be reset
   - Role model:
        - manager
        - customer
        - Customer: view/manage only own permitted resources; deposit, withdraw, transfer.
        - Manager: create/manage customers and accounts; block/unblock.
   - Customers:
        - create a new one
        - get information about existing one
        - update existing customer
        - soft delete and his accounts (can't be restored)
        - block customer and his accounts (can be restored)
   - Managing accounts:
        - create a new one
        - get information about existing one
        - update existing account
        - soft delete (can't be restored)
        - block account (can be restored)
        - deposit money onto an account
        - withdraw money from an account
        - transfer money from one account to another
        - Customer can own few accounts at the same time
        - Manager creates an account on behalf of a customer
        - Customer can't create accounts
        - Founds can't be deposited, withdrawn or transfered to or from a blocked account

   Some API should be accessible only from manager account because in real world customer cant obtain certain services without managers authorization.

   - Service should support different currencies and currency can be specified only during account creation.
   - Cross-currency is not supported, but code support integration of that feature
   - Value precision is DECIMAL(19, 4)
   - Amount can't go below zero
   - Transactions are atomic. Concurrent transfers must not allow the balance to go below zero.
   - Idempotency rule should be supported to prevent accidental double operations

2. Monitoring tools (metrics, logging, tracing, audit)
    - Structured logs with correlation/request IDs.
    - Metrics for latency, errors, and transaction outcomes.
    - Audit records for money movement and manager actions.
    - Do not write credentials, JWTs, or unnecessary personal data to logs.
3. Service should provide an interface to support customer and service level notifications
4. Security constraints:
   - How much money can be withdrawn or transferd from an account per day. It should be configurable.
        - Limits are per account
        - Limits for withdrawal and transfers are different
        - Timezone of the customer defines "day"
        - Failed operations dont count
        - Limits should be durable against concurrent modifications and should be done with atomicity and idempotency
5. Service should track history of accounts

## Non-functional requirements

1. Basic security steps to protect information about transactions and personal data.
2. Should respond in less than 2 seconds
3. The system should be able to handle 100 million users with minimal downtime.

## Technologies

Backend tech-stack

- Java 25 lts
- Spring (Boot, Security, Data Jpa): latest
- Hibernate (QueryDSL): latest
- Swagger OpenAPI: latest
- PostgreSQL: latest - general purpose DB
- Redis: latest - storing and invalidating jwt tokens 
