package com.thinhbui303.observability.alert;

import com.thinhbui303.observability.common.AlertColumn;
import com.thinhbui303.observability.common.AlertColumnContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AlertColumnContractVsConsumerEntityTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void alertsTable_ShouldMatchSharedContract_andConsumerEntityMapping() {
        List<Object[]> actual = jdbcTemplate.query(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name = 'alerts' ORDER BY ordinal_position",
                rs -> {
                    java.util.ArrayList<Object[]> rows = new java.util.ArrayList<>();
                    while (rs.next()) {
                        rows.add(new Object[]{rs.getString("column_name"), rs.getString("data_type"), rs.getString("is_nullable")});
                    }
                    return rows;
                });

        List<AlertColumn> contract = AlertColumnContract.ALERTS;
        assertThat(actual).hasSize(contract.size());
        for (int i = 0; i < contract.size(); i++) {
            AlertColumn c = contract.get(i);
            assertThat(actual.get(i)[0]).as("column name #%d", i).isEqualTo(c.name());
            assertThat(actual.get(i)[1]).as("type of %s", c.name()).isEqualTo(c.pgType());
            assertThat(actual.get(i)[2]).as("nullable of %s", c.name()).isEqualTo(c.nullable() ? "YES" : "NO");
        }
    }
}