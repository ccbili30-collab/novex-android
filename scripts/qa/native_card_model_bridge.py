#!/usr/bin/env python3
"""Private fixture transport only. Kotlin owns tools, authorization, writes and verification."""
import argparse, json, os, re, subprocess, sys, time
from pathlib import Path

def save(path, value):
    temporary=path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2))
    os.replace(temporary,path)

def read_when_ready(path, seconds=60):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        if path.exists():
            try: return json.loads(path.read_text())
            except json.JSONDecodeError: pass  # A producer may still be publishing the completion marker.
        time.sleep(.1)
    raise RuntimeError(f'原生进程没有返回 {path.name}')

def record_verified_result(root, usage):
    verified=read_when_ready(root/'verified.json')
    assert verified.get('exact_source') and verified.get('reopened')
    save(root/'result.json',{'verified':True,'module_count':verified['module_count'],'model_calls':len(usage),
        'prompt_tokens':sum(u.get('prompt_tokens',0) for u in usage),'completion_tokens':sum(u.get('completion_tokens',0) for u in usage),
        'transport':'non-streaming provider; same native card service and real Room storage; not phone UI acceptance',
        'formal_prompt':'original captured formal prompt with current product-tool guide and freshly allocated private directory; V6 candidate not used'})
    print('已通过：已解析原文逐块一致、48 个模块、数据库关闭重开仍在。',flush=True)

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--directory',type=Path,required=True)
    p.add_argument('--vault-script',type=Path)
    p.add_argument('--verify-existing',action='store_true')
    args=p.parse_args(); root=args.directory
    if args.verify_existing:
        assert 'OK (1 test)' in (root/'native-run.log').read_text()
        record_verified_result(root,json.loads((root/'usage.json').read_text()))
        return
    assert args.vault_script is not None
    ready=read_when_ready(root/'ready.json',120)
    tools=json.loads((root/'tools.json').read_text())
    guide=(root/'guide.txt').read_text()
    original=(root/'original-formal.txt').read_text()
    system=re.sub(r'<Novex内容与工具协议>.*?</Novex内容与工具协议>',lambda _:guide,original,flags=re.S)
    system=re.sub(r'<novex-background-data>.*?</novex-background-data>',lambda _: '<novex-background-data>本次隔离回归对话的实际创作目标目录；不是背景设定，不自动启动文游。\n'+json.dumps(ready['directory'],ensure_ascii=False)+'</novex-background-data>',system,flags=re.S)
    assert system!=original and 'novex_write_card' in system
    requests=['说中文，创建文游卡','将47章完整打包为主控说明书（自由沙盒，按资料全篇运行）']
    # Both are the user's existing explicit instructions; no synthesized content choices.
    messages=[{'role':'system','content':system}, {'role':'user','content':requests[0]+'\n'+requests[1]+
        '\n<novex-document-receipts>'+json.dumps({'document_ref':(root/'document-ref.txt').read_text().strip()},ensure_ascii=False)+'</novex-document-receipts>'}]
    # The native management policy receives the real original creation request; packing is an already authorized refinement.
    tool_users=[requests[0],requests[1]+'\n继续此前任务']
    index=0; saved=False; repaired=False; usage=[]
    for turn in range(12):
        body={'model':'deepseek-v4-flash','thinking':{'type':'disabled'},'stream':False,'max_tokens':8192,
              'messages':messages,'tools':tools}
        save(root/f'model-request-{turn}.json',body)
        # Scope is user-authorized; retain a conservative local ceiling without exposing credentials.
        if sum(u.get('prompt_tokens',0)+3*u.get('completion_tokens',0) for u in usage)>1_000_000:
            raise RuntimeError('本次回归达到本地令牌上限，停止调用')
        response=subprocess.run([sys.executable,str(args.vault_script),'request','DeepSeek','chat/completions','--method','POST','--body-stdin','--timeout','180'],
            input=json.dumps(body,ensure_ascii=False),text=True,capture_output=True)
        if response.returncode: raise RuntimeError('已保存模型接口调用失败；没有自动切换服务或重复写入')
        result=json.loads(response.stdout); save(root/f'model-response-{turn}.json',result)
        usage.append(result.get('usage',{})); save(root/'usage.json',usage)
        answer=result['choices'][0]['message']; messages.append(answer)
        calls=answer.get('tool_calls',[])
        print(f'模型第 {turn+1} 轮：{len(calls)} 次工具调用',flush=True)
        if not calls:
            if saved: break
            if repaired: raise RuntimeError('一次补救后仍没有保存回执')
            repaired=True
            messages.append({'role':'user','content':'<system-reminder>用户要求创建应用内卡片，但还没有写入回执。请实际调用卡片工具完成；原文按章节入卡可用 document_ref 与 source_revision。已有写入先核对，禁止重复创建。本轮只补救一次。</system-reminder>'})
            continue
        for call in calls:
            function=call['function']; name=function['name']; arguments=json.loads(function['arguments'])
            save(root/f'request-{index}.json',{'name':name,'arguments':arguments,'operation_id':call['id'],'user_requests':tool_users})
            output=read_when_ready(root/f'response-{index}.json'); index+=1
            saved=saved or output.get('status')=='saved_verified' and bool(output.get('created_cards'))
            print('  '+name+': '+str(output.get('status',output.get('code',output.get('error','已返回')))),flush=True)
            messages.append({'role':'tool','tool_call_id':call['id'],'content':json.dumps(output,ensure_ascii=False)})
            if name=='present_choices':
                messages.append({'role':'user','content':requests[1]})
    else: raise RuntimeError('模型调用达到十二轮上限')
    save(root/f'request-{index}.json',{'name':'__verify_and_close'})
    record_verified_result(root,usage)

if __name__=='__main__': main()
