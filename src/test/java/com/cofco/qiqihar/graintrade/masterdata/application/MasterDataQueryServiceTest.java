package com.cofco.qiqihar.graintrade.masterdata.application;

import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessPeriod;
import com.cofco.qiqihar.graintrade.masterdata.domain.Cultivar;
import com.cofco.qiqihar.graintrade.masterdata.domain.ObjectType;
import com.cofco.qiqihar.graintrade.masterdata.domain.PageDefinition;
import com.cofco.qiqihar.graintrade.masterdata.domain.Product;
import com.cofco.qiqihar.graintrade.masterdata.domain.Region;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MasterDataQueryServiceTest {

    @Test
    void reportsMissingPageDefinitionAsAControlledClientError() {
        MasterDataQueryService service = new MasterDataQueryService(new EmptyRepository());

        assertThatThrownBy(() -> service.pageDefinition("CORN", "MARKET", "QUALITY"))
                .isInstanceOf(ClientRequestException.class)
                .satisfies(error -> {
                    ClientRequestException exception = (ClientRequestException) error;
                    assertThat(exception.code()).isEqualTo("MASTER_DATA_NOT_FOUND");
                    assertThat(exception.clientMessage()).isEqualTo("Requested master data does not exist");
                });
    }

    @Test
    void ownsReadOnlyTransactionAtTheApplicationBoundary() {
        Transactional transaction = MasterDataQueryService.class.getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
    }

    private static final class EmptyRepository implements MasterDataRepository {

        @Override
        public List<Region> findRegions() {
            return List.of();
        }

        @Override
        public List<Product> findProducts() {
            return List.of();
        }

        @Override
        public List<Cultivar> findCultivarsByProductCode(String productCode) {
            return List.of();
        }

        @Override
        public List<ObjectType> findObjectTypes(String productCode, String domain) {
            return List.of();
        }

        @Override
        public List<BusinessPeriod> findBusinessPeriods() {
            return List.of();
        }

        @Override
        public Optional<PageDefinition> findPageDefinition(String productCode, String domain, String pageKind) {
            return Optional.empty();
        }
    }
}
