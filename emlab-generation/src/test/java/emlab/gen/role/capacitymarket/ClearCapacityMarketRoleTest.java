/*******************************************************************************
 * Copyright 2013 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package emlab.gen.role.capacitymarket;

import org.apache.log4j.Logger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import emlab.gen.domain.agent.EnergyProducer;
import emlab.gen.domain.agent.Regulator;
import emlab.gen.domain.contract.Loan;
import emlab.gen.domain.gis.Zone;
import emlab.gen.domain.market.capacity.CapacityDispatchPlan;
import emlab.gen.domain.market.capacity.CapacityMarket;
import emlab.gen.domain.market.electricity.ElectricitySpotMarket;
import emlab.gen.domain.technology.PowerGeneratingTechnology;
import emlab.gen.domain.technology.PowerPlant;
import emlab.gen.repository.CapacityMarketRepository;
import emlab.gen.repository.Reps;

/**
 * @author Kaveri
 * 
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration({ "/emlab-gen-test-context.xml" })
@Transactional
public class ClearCapacityMarketRoleTest {

    Logger logger = Logger.getLogger(ClearCapacityMarketRole.class);

    @Autowired
    Reps reps;

    @Autowired
    CapacityMarketRepository capacityMarketRepository;

    @Autowired
    ClearCapacityMarketRole clearCapacityMarketRoleTest;

    @Test
    public void ClearCapacityMarketBasicFunctionality() {

        Zone zone = new Zone();
        zone.persist();

        CapacityMarket market = new CapacityMarket();
        market.setZone(zone);
        market.persist();

        Regulator regulator = new Regulator();

        regulator.setDemandTarget(100);
        regulator.setCapacityMarketPriceCap(10);
        regulator.setLongTermCapacityContractLengthinYears(15);
        // regulator.setReserveMargin(0.156);
        regulator.setReserveDemandLowerMargin(0.15);
        regulator.setReserveDemandUpperMargin(0.05);
        regulator.setZone(zone);
        regulator.persist();

        ElectricitySpotMarket market1 = new ElectricitySpotMarket();
        market1.setName("Market1");
        market1.setZone(zone);
        market1.persist();

        PowerGeneratingTechnology coal1 = new PowerGeneratingTechnology();
        coal1.persist();

        EnergyProducer e1 = new EnergyProducer();
        e1.setName("E1");
        e1.setCash(0);
        e1.setPriceMarkUp(1);
        e1.persist();

        Loan l1 = new Loan();
        l1.setAmountPerPayment(6000);
        l1.setNumberOfPaymentsDone(10);
        l1.setTotalNumberOfPayments(15);
        l1.persist();

        PowerPlant pp1 = new PowerPlant();
        pp1.setTechnology(coal1);
        pp1.setOwner(e1);
        pp1.setActualFixedOperatingCost(99000);
        pp1.setLoan(l1);
        pp1.persist();

        CapacityDispatchPlan cdp1 = new CapacityDispatchPlan();
        cdp1.setAmount(8);
        cdp1.setPrice(0);
        cdp1.setTime(0l);
        cdp1.setStatus(1);
        cdp1.setPlant(pp1);
        cdp1.persist();

        CapacityDispatchPlan cdp2 = new CapacityDispatchPlan();
        cdp2.setAmount(20);
        cdp2.setPrice(0);
        cdp2.setTime(0l);
        cdp2.setStatus(1);
        cdp2.setPlant(pp1);
        cdp2.persist();

        CapacityDispatchPlan cdp3 = new CapacityDispatchPlan();
        cdp3.setAmount(70);
        cdp3.setPrice(1);
        cdp3.setTime(0l);
        cdp3.setStatus(1);
        cdp3.setPlant(pp1);
        cdp3.persist();

        CapacityDispatchPlan cdp4 = new CapacityDispatchPlan();
        cdp4.setAmount(10);
        cdp4.setPrice(20);
        cdp4.setTime(0l);
        cdp4.setStatus(1);
        cdp4.setPlant(pp1);
        cdp4.persist();

        clearCapacityMarketRoleTest.act(regulator);

        logger.warn("Status of CDP 1 is " + cdp1.getStatus());
        logger.warn("Status of CDP 2 is " + cdp2.getStatus());
        logger.warn("Status of CDP 3 is " + cdp3.getStatus());
        logger.warn("Status of (overpriced) CDP 4 is " + cdp4.getStatus());

        // CapacityClearingPoint capacityClearingPoint =
        // reps.capacityMarketRepository
        // .findOneCapacityClearingPointForTime(0);

        // logger.warn("Clearing point Price" +
        // capacityClearingPoint.getPrice());
        // logger.warn("Clearing Point Volume" +
        // capacityClearingPoint.getVolume());

    }

}
