#!/usr/bin/env python3
"""Test-only provider transport. Production Kotlin builds prompts and owns learning state."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

def save(path, value):
    temporary=path.with_suffix(path.suffix+'.tmp')
    with temporary.open('w',encoding='utf-8') as handle:
        json.dump(value,handle,ensure_ascii=False,indent=2)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary,path)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--authorization',required=True,type=Path)
    parser.add_argument('--vault-script',required=True,type=Path)
    parser.add_argument('--ledger',required=True,type=Path)
    args=parser.parse_args()
    authorization=json.loads(args.authorization.read_text())
    assert authorization['approved'] is True and authorization['scope'] in ('task-a-small-baseline', 'task-b-small-handoff', 'task-b-scale')
    payload=json.load(sys.stdin)
    prices={'deepseek-v4-flash':(0.44,1.32), 'deepseek-v4-pro':(1.32,3.96)}
    allowed=authorization.get('models',[authorization.get('model')])
    assert payload['model'] in allowed and payload['model'] in prices
    if authorization['scope'] in ('task-a-small-baseline', 'task-b-scale'):
        assert payload['model']=='deepseek-v4-flash'
    input_price,output_price=prices[payload['model']]
    assert payload.get('thinking')=={'type':'disabled'}
    assert 0 < payload['max_tokens'] <= 4096
    assert payload.get('stream') is False
    args.ledger.mkdir(parents=True,exist_ok=True)
    with (args.ledger/'budget.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        previous=[json.loads(path.read_text()) for path in sorted(args.ledger.glob('call-*/receipt.json'))]
        charged=sum(x.get('charged_usd',x['reserved_usd']) for x in previous)
        encoded=json.dumps(payload,ensure_ascii=False).encode('utf-8')
        # Peak, uncached official prices are conservative even during off-peak/cached requests.
        reserved_input=len(encoded)+4096
        reserved=reserved_input*input_price/1_000_000+payload['max_tokens']*output_price/1_000_000
        scope_cap=5.0 if authorization['scope']=='task-b-scale' else 1.0
        if charged+reserved > min(float(authorization['max_usd']),scope_cap):
            raise RuntimeError('当前开发测试累计预算已达上限；没有发起本次模型请求')
        call=args.ledger/f'call-{len(previous)+1:04d}'
        call.mkdir()
        save(call/'request.json',payload)
        receipt=dict(status='reserved',started_at=time.time(),model=payload['model'],
                     request_sha256=hashlib.sha256(encoded).hexdigest(),reserved_input_tokens=reserved_input,
                     reserved_output_tokens=payload['max_tokens'],reserved_usd=reserved,
                     pricing_basis='2026-09-07 official peak uncached USD; conservative without cache discount')
        save(call/'receipt.json',receipt)
        result=subprocess.run([sys.executable,str(args.vault_script),'request','deepseek','chat/completions',
                               '--method','POST','--body-stdin','--timeout','180'],
                              input=encoded,capture_output=True,timeout=200)
        if result.returncode:
            receipt.update(status='request_failed_or_uncertain',ended_at=time.time(),charged_usd=reserved,
                           accounting='reserved estimate; no automatic retry')
            save(call/'receipt.json',receipt)
            raise RuntimeError('模型调用失败或状态不明；已保留本次预算占用，不自动重试')
        response=json.loads(result.stdout)
        save(call/'response.json',response)
        usage=response.get('usage',{})
        actual_input=usage.get('prompt_tokens')
        actual_output=usage.get('completion_tokens')
        estimated=not isinstance(actual_input,int) or not isinstance(actual_output,int)
        charged_call=reserved if estimated else actual_input*input_price/1_000_000+actual_output*output_price/1_000_000
        receipt.update(status='response_saved',ended_at=time.time(),input_tokens=actual_input,
                       output_tokens=actual_output,usage_estimated=estimated,charged_usd=charged_call,
                       accounting='conservative price estimate, provider token counts retained; not provider billing receipt')
        save(call/'receipt.json',receipt)
        print(json.dumps(response,ensure_ascii=False))

if __name__=='__main__':
    try: main()
    except Exception as error:
        print(f'测试模型调用停止：{error}',file=sys.stderr)
        raise SystemExit(1)
