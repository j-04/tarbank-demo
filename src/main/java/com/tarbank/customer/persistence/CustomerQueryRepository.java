package com.tarbank.customer.persistence;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.tarbank.customer.domain.CustomerEntity;
import com.tarbank.customer.domain.QCustomerEntity;
import com.tarbank.security.domain.UserStatus;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class CustomerQueryRepository {
    private final JPAQueryFactory queries;

    public CustomerQueryRepository(JPAQueryFactory queries) {
        this.queries = queries;
    }

    public List<CustomerEntity> findPage(UserStatus status,
                                         Long afterCustomerId,
                                         int size) {
        QCustomerEntity customer = QCustomerEntity.customerEntity;
        BooleanBuilder predicate = new BooleanBuilder();
        if (status != null) predicate.and(customer.user.status.eq(status));
        if (afterCustomerId != null) predicate.and(customer.userId.gt(afterCustomerId));
        return queries.selectFrom(customer)
                      .join(customer.user)
                      .fetchJoin()
                      .where(predicate)
                      .orderBy(customer.userId.asc())
                      .limit(size)
                      .fetch();
    }
}