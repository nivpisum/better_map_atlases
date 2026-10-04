/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurveyAdaptiveTest {
    @Test void aCurrentHeightOverviewKeepsTheHudSupportButHistoryDoesNotBorrowIt() {
        var reader = new SurveyProjection.Support(2575, 841, 25 * 16 + 8);
        assertSame(reader, SurveyProjection.readerSupport(25 * 16 + 8, 25 * 16 + 8, reader));
        assertNull(SurveyProjection.readerSupport(24 * 16 + 8, 25 * 16 + 8, reader));
        assertNull(SurveyProjection.readerSupport(25 * 16, 25 * 16 + 8, reader));
        assertNull(SurveyProjection.readerSupport(25 * 16 + 8, 25 * 16 + 8, null));
    }
    private SurveyProjection.Floor f(int x, int z, int y) {
        return new SurveyProjection.Floor(0,x+128*z,x,z,y*16,y*16,(y+3)*16);
    }
    @Test void theRecordedSupportWinsWhenRemoteLowerFloorsOutvoteIt() {
        List<SurveyProjection.Floor> low=new ArrayList<>(),high=new ArrayList<>(),all=new ArrayList<>();
        for(int x=0;x<12;x++){
            int y=x==0?23:24;
            low.add(new SurveyProjection.Floor(0,x,x,0,y*16,y*16,(y+2)*16));
            high.add(f(x,0,x==0?25:26));
        }
        all.addAll(low);all.addAll(high);var index=new SurveyProjection.Index(all);
        assertFalse(index.select(25*16).allows(high.get(0)),"reproduces the old whole-sheet exclusion");
        for(int y:new int[]{25,26}){
            var at=index.select(y*16,512,512,new SurveyProjection.Support(0,0,y*16));
            assertTrue(high.stream().allMatch(at::allows));assertTrue(low.stream().noneMatch(at::allows));
        }
    }
    @Test void onlyARecordedSupportInTheReadersAirIntervalCanChooseTheSheet() {
        var low=f(-2,-3,-10);var high=f(-2,-3,-6);var index=new SurveyProjection.Index(List.of(low,high));
        var at=index.select(-6*16,512,512,new SurveyProjection.Support(-2,-3,-6*16));
        assertTrue(at.allows(high));assertFalse(at.allows(low));
        assertTrue(at.matches(-6*16,512,512,new SurveyProjection.Support(-2,-3,-6*16+8)));
        assertFalse(at.matches(-6*16,512,512,new SurveyProjection.Support(-2,-3,-10*16)));
        assertFalse(at.matches(-6*16,512,512,null));
        assertTrue(index.select(-10*16,512,512,null).allows(low),"manual history keeps its chosen height");
    }
    @Test void interleavedHeightsAndSupportsReuseTheirOwnSelections() {
        var index=new SurveyProjection.Index(List.of(f(0,0,25),f(0,0,30)));
        var low=index.select(25*16,512,512,new SurveyProjection.Support(0,0,25*16));
        var high=index.select(30*16,512,512,null);
        var manual=index.select(25*16,512,512,null);
        for(int i=0;i<20;i++){
            assertSame(high,index.select(30*16,512,512,null));
            assertSame(manual,index.select(25*16,512,512,null));
            assertSame(low,index.select(25*16,512,512,new SurveyProjection.Support(0,0,25*16+8)));
        }
    }
    @Test void stairsDoNotMergeVerticallyStackedFloors() {
        List<SurveyProjection.Floor> low=new ArrayList<>(),high=new ArrayList<>(),all=new ArrayList<>();
        for(int x=0;x<12;x++){low.add(f(x,0,26));high.add(f(x,0,30));}
        all.addAll(low);all.addAll(high);
        for(int x=0;x<=4;x++)all.add(f(x,1,26+x));
        var at=new SurveyProjection.Index(all).select(30*16);
        assertTrue(high.stream().allMatch(at::allows));
        assertTrue(low.stream().noneMatch(at::allows),"an indirect stair path must not reactivate the whole lower floor");
    }
    @Test void aSmallWalkwayExcludesItsWholeLowerSheetWithoutBanningUnrelatedSameHeightTerrain() {
        List<SurveyProjection.Floor> low=new ArrayList<>(),all=new ArrayList<>();
        for(int x=0;x<12;x++)low.add(f(x,0,26));all.addAll(low);
        var bridge=f(6,0,30);var unrelated=f(100,100,26);all.add(bridge);all.add(unrelated);
        var at=new SurveyProjection.Index(all).select(30*16);
        assertTrue(at.allows(bridge));assertTrue(low.stream().noneMatch(at::allows));
        assertTrue(at.allows(unrelated),"global Y bans would wrongly erase this nonoverlapping terrain");
    }
    @Test void oneBlockMotionWithinTheSameStoreyDoesNotSwitchMineSheets() {
        List<SurveyProjection.Floor> low=new ArrayList<>(),high=new ArrayList<>(),all=new ArrayList<>();
        for(int x=0;x<10;x++){low.add(f(x,0,26));high.add(f(x,0,30));}
        all.addAll(low);all.addAll(high);var index=new SurveyProjection.Index(all);
        for(int y:new int[]{30,31}){var at=index.select(y*16);assertTrue(high.stream().allMatch(at::allows));assertTrue(low.stream().noneMatch(at::allows));}
    }
    @Test void disconnectedPassagesOnTheSameUpperStoreyBothRemainVisible() {
        List<SurveyProjection.Floor> low=new ArrayList<>(),high=new ArrayList<>(),all=new ArrayList<>();
        for(int x=0;x<18;x++){low.add(f(x,0,26));if(x<5||x>12)high.add(f(x,0,30));}
        all.addAll(low);all.addAll(high);var at=new SurveyProjection.Index(all).select(30*16);
        assertTrue(high.stream().allMatch(at::allows));assertTrue(low.stream().noneMatch(at::allows));
    }
    @Test void contextKeepsNearbySlopesWithoutUnfoldingTheirWholeHeight() {
        List<SurveyProjection.Floor> slope=new ArrayList<>();
        for(int x=0;x<40;x++)slope.add(f(x,0,x));var at=new SurveyProjection.Index(slope).select(10*16);
        assertTrue(slope.subList(0,22).stream().allMatch(at::allows));
        assertTrue(slope.subList(23,40).stream().noneMatch(at::allows));
        assertNotEquals(Long.MAX_VALUE,SurveyProjection.rank(30*16,30*16,33*16,10*16,512,512,30*16,33*16));
        assertEquals(Long.MAX_VALUE,SurveyProjection.rank(50*16,50*16,53*16,10*16,512,512,50*16,53*16));
    }
    @Test void theContextEdgeFadesGraduallyAndHandlesNegativeHeights() {
        assertEquals(255,SurveyProjection.contextAlpha(-9*16,-33*16,512,512));
        assertEquals(0,SurveyProjection.contextAlpha(-1*16,-33*16,512,512));
        int previous=255;
        for(int gap=25;gap<=32;gap++){int alpha=SurveyProjection.contextAlpha(gap*16,0,512,512);assertTrue(alpha<=previous&&previous-alpha<=32);previous=alpha;}
    }
    @Test void aDetachedClosedCaveFadesFromNineToTwelveBlocksInEitherDirection() {
        var cave=f(100,100,20);var mine=f(0,0,30);var index=new SurveyProjection.Index(List.of(cave,mine));
        assertEquals(160,index.select(29*16).alpha(cave));
        assertEquals(106,index.select(30*16).alpha(cave));
        assertEquals(53,index.select(31*16).alpha(cave));
        assertEquals(0,index.select(32*16).alpha(cave));
        assertFalse(index.select(33*16).allows(cave));
        assertEquals(106,index.select(10*16).alpha(cave));
        assertEquals(0,index.select(8*16).alpha(cave));
        assertFalse(index.select(7*16).allows(cave));
    }
    @Test void aConnectedSlopeCannotExemptItsRemoteHeightsFromTheHorizon() {
        List<SurveyProjection.Floor> slope=new ArrayList<>();
        for(int x=0;x<=25;x++)slope.add(f(x,0,x));
        var detached=f(100,100,25);var all=new ArrayList<>(slope);all.add(detached);
        var at=new SurveyProjection.Index(all).select(0,512,512,new SurveyProjection.Support(0,0,0));
        assertTrue(at.allows(slope.get(9)));assertTrue(at.alpha(slope.get(9))>0);
        assertFalse(at.allows(slope.get(25)));assertEquals(0,at.alpha(slope.get(25)));
        assertFalse(at.allows(detached));assertEquals(0,at.alpha(detached));
    }
}
