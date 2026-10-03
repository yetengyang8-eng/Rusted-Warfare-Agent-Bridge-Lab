#!/usr/bin/env python3
"""Bounded streaming extraction of capacity evidence from a Battle JSONL.

No whole-file read. Observation commitment excludes unexported specialist/paid
queues, and sample-held durations are diagnostic estimates, not exact idle time.
"""
import argparse,collections,hashlib,json
from pathlib import Path

SELECTED={'summary','strategy_capacity','production_surplus','production_idle','factory_target_increase_blocked',
    'factory_target_increased','spend','surplus_spending_evaluated','surplus_role_ordered','production_decision',
    'production_deferred','production_queue_finished','report_provenance','battle_config','factory_completed',
    'production_capacity_assessment','military_capacity_increased','production_capacity_after_expansion'}

def scan(path):
    digest=hashlib.sha256();counts=collections.Counter();samples={};last={};stats={};spend=collections.Counter()
    idle=collections.Counter();lines=bad=0;target=hard=None;previous=None;final=None
    duration={'sampleHeldAtOrAboveStrategyTargetMs':0,'sampleHeldAtOrAboveHardCapMs':0,'sampleHeldBelowStrategyTargetMs':0}
    first_full=None
    with path.open('rb') as source:
        for raw in source:
            digest.update(raw);lines+=1
            try:row=json.loads(raw)
            except (ValueError,UnicodeError):bad+=1;continue
            event=row.get('event');data=row.get('data',{})
            if not isinstance(data,dict):continue
            if event=='strategy_capacity':target=data.get('armyTarget');hard=data.get('hardSafetyCap')
            if event=='spend':spend[data.get('category','UNKNOWN')]+=data.get('cost',0)
            if event=='production_idle':idle[data.get('reason','UNKNOWN')]+=1
            if event=='observation':
                own=[u for u in data.get('ownUnits',[]) if not u.get('dead',False) and u.get('hp',0)>0]
                armed=sum(bool(u.get('mobile')) and bool(u.get('canAttack')) for u in own)
                queues=sum(max(0,u.get('productionQueue',0)) for u in own if u.get('type')=='landFactory')
                current={'gameTimeMs':data.get('gameTimeMs',0),'credits':data.get('player',{}).get('credits'),
                    'mobileArmed':armed,'landFactoryQueue':queues,'observableCommitted':armed+queues,
                    'strategyTarget':target,'hardCap':hard}
                if previous and previous['strategyTarget'] is not None:
                    dt=max(0,current['gameTimeMs']-previous['gameTimeMs'])
                    duration['sampleHeldAtOrAboveStrategyTargetMs' if previous['observableCommitted']>=previous['strategyTarget'] else 'sampleHeldBelowStrategyTargetMs']+=dt
                    if previous['hardCap'] is not None and previous['observableCommitted']>=previous['hardCap']:
                        duration['sampleHeldAtOrAboveHardCapMs']+=dt
                if target is not None and armed+queues>=target and first_full is None:first_full=current
                previous=current;final=current
            if event not in SELECTED and not str(event).startswith('production_facility_'):continue
            counts[event]+=1
            if len(samples.setdefault(event,[]))<3:samples[event].append(data)
            last[event]=data
            for key,value in data.items():
                if isinstance(value,(int,float)) and not isinstance(value,bool):
                    label=event+'.'+key
                    if label not in stats:stats[label]={'n':0,'first':value,'last':value,'min':value,'max':value,'sum':0}
                    s=stats[label];s['n']+=1;s['last']=value;s['min']=min(s['min'],value);s['max']=max(s['max'],value);s['sum']+=value
    for s in stats.values():s['mean']=round(s.pop('sum')/s['n'],3)
    return dict(schemaVersion=1,rawPath=str(path.resolve()),rawBytes=path.stat().st_size,rawSha256=digest.hexdigest(),
        lines=lines,malformedJson=bad,eventCounts=dict(counts),firstSamples=samples,last=last,numeric=stats,
        spendByCategory=dict(spend),productionIdleReasons=dict(idle),finalObservation=final,
        firstObservableTargetFull=first_full,observableCapacityDurations=duration,
        limitations=['Counts are exported event counts; old refusal/idle logs emit transitions, not every blocked frame.',
            'Observable commitment is own mobile armed plus exported landFactory queue; specialist and unobserved paid commitments are omitted.',
            'Durations hold each sampled observation until the next; missing commitments and between-sample changes prevent exact causal blocked-time attribution.',
            'PARTIAL/ONGOING is not victory; available slots plus idle factories do not prove a producer bottleneck.'])

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('raw',type=Path);parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args();result=scan(args.raw);args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:result[k] for k in ('rawBytes','rawSha256','lines','malformedJson','finalObservation','observableCapacityDurations')},ensure_ascii=False))

if __name__=='__main__':main()
