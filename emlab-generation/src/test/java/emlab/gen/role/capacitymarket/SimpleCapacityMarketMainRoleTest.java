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

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import emlab.gen.domain.agent.EnergyProducer;
import emlab.gen.domain.agent.Regulator;
import emlab.gen.domain.gis.Zone;
import emlab.gen.domain.market.capacity.CapacityDispatchPlan;
import emlab.gen.domain.market.capacity.CapacityMarket;
import emlab.gen.domain.market.electricity.ElectricitySpotMarket;
import emlab.gen.domain.market.electricity.Segment;
import emlab.gen.domain.market.electricity.SegmentClearingPoint;
import emlab.gen.domain.market.electricity.SegmentLoad;
import emlab.gen.domain.technology.PowerGeneratingTechnology;
import emlab.gen.domain.technology.PowerGridNode;
import emlab.gen.domain.technology.PowerPlant;
import emlab.gen.repository.Reps;
import emlab.gen.trend.TriangularTrend;

/**
 * @author Kaveri
 * 
 */

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration({ "/emlab-gen-test-context.xml" })
@Transactional
public class SimpleCapacityMarketMainRoleTest {

    @Autowired
    Reps reps;

    @Autowired
    SimpleCapacityMarketMainRole simpleCapacityMarketMainRole;

    @Test
    public void CapacityMarketMainFunctionality() {
        Zone zone = new Zone();
        zone.persist();
        Regulator regulator = new Regulator();
        regulator.setTargetPeriod(0);
        regulator.setReserveMargin(0.15);
        regulator.setDemandTarget(100);
        regulator.setCapacityMarketPriceCap(10);
        regulator.setReserveDemandLowerMargin(0.15);
        regulator.setReserveDemandUpperMargin(0.05);
        regulator.setNumberOfYearsLookingBackToForecastDemand(3);
        regulator.setZone(zone);
        regulator.persist();

        Segment S1 = new Segment();
        S1.setLengthInHours(20);
        S1.persist();

        Segment S2 = new Segment();
        S2.setLengthInHours(30);
        S2.persist();

        SegmentLoad SG1 = new SegmentLoad();
        SG1.setSegment(S2);
        SG1.setBaseLoad(2500);
        SG1.persist();
        // SegmentLoad SG2 = new SegmentLoad();
        // SG2.setSegment(S2);
        // SG2.setBaseLoad(2000);

        SegmentLoad SG3 = new SegmentLoad();
        SG3.setSegment(S1);
        SG3.setBaseLoad(3700);

        // SegmentLoad SG4 = new SegmentLoad();
        // SG4.setSegment(S1);
        // SG4.setBaseLoad(4000);

        // SG2.persist();
        SG3.persist();
        // SG4.persist();

        Set<SegmentLoad> segmentLoads1 = new HashSet<SegmentLoad>();
        segmentLoads1.add(SG1);
        segmentLoads1.add(SG3);
        //
        // TimeSeriesImpl demandGrowthTrend = new TimeSeriesImpl();
        // int lengthOfSeries = 50;
        // double[] timeSeries = new double[lengthOfSeries];
        // timeSeries[0] = 1;
        // for (int i = 1; i <= lengthOfSeries; i++) {
        // timeSeries[i] = timeSeries[i - 1] * 1.02;
        // }

        TriangularTrend demandGrowthTrend = new TriangularTrend();
        demandGrowthTrend.setMax(2);
        demandGrowthTrend.setMin(1);
        demandGrowthTrend.setStart(1);
        demandGrowthTrend.setTop(1);

        PowerGridNode location = new PowerGridNode();
        location.setZone(zone);
        location.persist();

        // demandGrowthTrend.setTimeSeries(timeSeries);
        // demandGrowthTrend.setStartingYear(0);
        demandGrowthTrend.persist();

        ElectricitySpotMarket market1 = new ElectricitySpotMarket();
        market1.setName("Market1");
        market1.setLoadDurationCurve(segmentLoads1);
        market1.setDemandGrowthTrend(demandGrowthTrend);
        market1.setZone(zone);
        market1.persist();

        TriangularTrend gasFixedOperatingCostTimeSeries = new TriangularTrend();
        // gasFixedOperatingCostTimeSeries[0]
        gasFixedOperatingCostTimeSeries.setMax(1.10);
        gasFixedOperatingCostTimeSeries.setMin(0.96);
        gasFixedOperatingCostTimeSeries.setStart(0.25);
        gasFixedOperatingCostTimeSeries.setTop(1.03);
        gasFixedOperatingCostTimeSeries.persist();

        TriangularTrend coalFixedOperatingCostTimeSeries = new TriangularTrend();
        coalFixedOperatingCostTimeSeries.setMax(1.05);
        coalFixedOperatingCostTimeSeries.setMin(0.97);
        coalFixedOperatingCostTimeSeries.setStart(100);
        coalFixedOperatingCostTimeSeries.setTop(1.01);
        coalFixedOperatingCostTimeSeries.persist();

        PowerGeneratingTechnology coal1 = new PowerGeneratingTechnology();
        coal1.setFixedOperatingCostTimeSeries(coalFixedOperatingCostTimeSeries);
        PowerGeneratingTechnology coal2 = new PowerGeneratingTechnology();
        coal2.setFixedOperatingCostTimeSeries(coalFixedOperatingCostTimeSeries);
        PowerGeneratingTechnology gas1 = new PowerGeneratingTechnology();
        gas1.setFixedOperatingCostTimeSeries(gasFixedOperatingCostTimeSeries);
        PowerGeneratingTechnology gas2 = new PowerGeneratingTechnology();
        gas2.setFixedOperatingCostTimeSeries(gasFixedOperatingCostTimeSeries);

        coal1.persist();
        coal2.persist();
        gas1.persist();
        gas2.persist();

        EnergyProducer e1 = new EnergyProducer();
        e1.setName("E1");
        e1.setCash(0);
        e1.setPriceMarkUp(1);

        EnergyProducer e2 = new EnergyProducer();
        e2.setCash(0);
        e2.setPriceMarkUp(1);
        e2.setName("E2");

        e1.persist();
        e2.persist();

        PowerPlant pp1 = new PowerPlant();
        pp1.setName("plant 1");
        pp1.setTechnology(coal1);
        pp1.setOwner(e1);
        pp1.setActualFixedOperatingCost(99000);
        pp1.setActualPermittime(0);
        pp1.setActualLeadtime(0);
        pp1.setConstructionStartTime(-1);
        pp1.setDismantleTime(99);
        pp1.setLocation(location);
        // pp1.setName("PP1");

        PowerPlant pp2 = new PowerPlant();
        pp2.setName("Plant 2");
        pp2.setActualPermittime(0);
        pp2.setActualLeadtime(0);
        pp2.setDismantleTime(99);
        pp2.setConstructionStartTime(-1);
        pp2.setTechnology(coal2);
        pp2.setOwner(e2);
        pp2.setActualFixedOperatingCost(111000);
        pp2.setLocation(location);
        // pp2.setName("PP2");

        PowerPlant pp3 = new PowerPlant();
        pp3.setName("Plant 3");
        pp3.setActualPermittime(0);
        pp3.setDismantleTime(99);
        pp3.setActualLeadtime(0);
        pp3.setConstructionStartTime(-1);
        pp3.setTechnology(gas1);
        pp3.setOwner(e2);
        pp3.setActualFixedOperatingCost(56000);

        pp3.setLocation(location);

        PowerPlant pp4 = new PowerPlant();
        pp4.setName("Plant 4");
        pp4.setActualPermittime(0);
        pp4.setActualLeadtime(0);
        pp4.setConstructionStartTime(-1);
        pp4.setDismantleTime(99);
        pp4.setTechnology(gas2);
        pp4.setOwner(e1);
        pp4.setActualFixedOperatingCost(65000);
        pp4.setLocation(location);

        PowerPlant pp5 = new PowerPlant();
        pp5.setName("Plant 5");
        pp5.setActualPermittime(0);
        pp5.setActualLeadtime(0);
        pp5.setConstructionStartTime(-1);
        pp5.setDismantleTime(99);
        pp5.setTechnology(gas1);
        pp5.setOwner(e2);
        pp5.setActualFixedOperatingCost(56000);
        pp5.setLocation(location);

        PowerPlant pp6 = new PowerPlant();
        pp6.setName("Plant 6");
        pp6.setActualPermittime(0);
        pp6.setActualLeadtime(0);
        pp6.setConstructionStartTime(-1);
        pp6.setDismantleTime(99);
        pp6.setTechnology(gas2);
        pp6.setOwner(e1);
        pp6.setActualFixedOperatingCost(65000);
        pp6.setLocation(location);

        pp1.persist();
        pp2.persist();
        pp3.persist();
        pp4.persist();
        pp5.persist();
        pp6.persist();

        CapacityMarket cMarket = new CapacityMarket();
        cMarket.setName("Capaciteit Markt");
        cMarket.setRegulator(regulator);
        cMarket.setZone(zone);
        cMarket.persist();

        SegmentClearingPoint clearingPoint1 = new SegmentClearingPoint();
        clearingPoint1.setSegment(S1);
        clearingPoint1.setAbstractMarket(market1);
        clearingPoint1.setPrice(25);
        clearingPoint1.setTime(0);
        clearingPoint1.persist();

        SegmentClearingPoint clearingPoint2 = new SegmentClearingPoint();
        clearingPoint2.setSegment(S2);
        clearingPoint2.setAbstractMarket(market1);
        clearingPoint2.setPrice(7);
        clearingPoint2.setTime(0);
        clearingPoint2.persist();

        CapacityDispatchPlan cdp1 = new CapacityDispatchPlan();
        cdp1.setBidder(e1);
        cdp1.setBiddingMarket(market1);
        cdp1.setTime(0l);
        cdp1.setAcceptedAmount(100);
        cdp1.setPlant(pp1);
        cdp1.setStatus(3);
        cdp1.persist();

        CapacityDispatchPlan cdp2 = new CapacityDispatchPlan();
        cdp2.setBidder(e1);
        cdp2.setBiddingMarket(market1);
        cdp2.setTime(0l);
        cdp2.setAcceptedAmount(80);
        cdp2.setPlant(pp2);
        cdp2.setStatus(2);
        cdp2.persist();

        CapacityDispatchPlan cdp3 = new CapacityDispatchPlan();
        cdp3.setBidder(e2);
        cdp3.setBiddingMarket(market1);
        cdp3.setTime(0l);
        cdp3.setAcceptedAmount(150);
        cdp3.setPlant(pp3);
        cdp3.setStatus(3);
        cdp3.persist();

        CapacityDispatchPlan cdp4 = new CapacityDispatchPlan();
        cdp4.setBidder(e2);
        cdp4.setBiddingMarket(market1);
        cdp4.setTime(0l);
        cdp4.setAcceptedAmount(100);
        cdp4.setPlant(pp4);
        cdp4.setStatus(3);
        cdp4.persist();

        simpleCapacityMarketMainRole.act(cMarket);

    }

}
