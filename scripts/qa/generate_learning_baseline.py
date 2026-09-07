#!/usr/bin/env python3
"""Generate deterministic synthetic reading material; gold answers never enter source files."""
import argparse
import hashlib
import json
from pathlib import Path
from xml.sax.saxutils import escape
from zipfile import ZipFile, ZIP_DEFLATED, ZipInfo

NOTICE = '测试资料，不是用户正式设定。本文属于合成作品《生生·验收用本》，所有人物与路线仅供软件测试。'

def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')

def docx(path, paragraphs, image_only=False):
    body = ''.join('<w:p><w:r><w:t xml:space="preserve">'+escape(p)+'</w:t></w:r></w:p>' for p in paragraphs)
    if image_only:
        body = '<w:p><w:r><w:drawing><a:blip r:embed="img1"/></w:drawing></w:r></w:p>'
    files = {
        '[Content_Types].xml': '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
        '_rels/.rels': '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="main" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
        'word/document.xml': '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><w:body>'+body+'</w:body></w:document>',
    }
    if image_only:
        files['word/_rels/document.xml.rels'] = '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="img1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/scan.svg"/></Relationships>'
        files['word/media/scan.svg'] = '<svg xmlns="http://www.w3.org/2000/svg"><text>测试扫描附录：此处文字未由正文解析器提取，不能计为已读。</text></svg>'
    with ZipFile(path, 'w', ZIP_DEFLATED) as archive:
        for name, text in files.items():
            info = ZipInfo(name, date_time=(2026, 9, 7, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            archive.writestr(info, text.encode('utf-8'))

def create(root, target):
    if root.exists():
        raise SystemExit(f'拒绝覆盖已有验收资料：{root}')
    sources = root/'sources'
    sources.mkdir(parents=True)
    gold = root/'private-gold'
    gold.mkdir()
    documents, facts = [], []

    def add(name, route, entries, status='正式', revision='1'):
        paragraphs = [NOTICE, f'资料：{name}；路线：{route}；版本：{revision}；状态：{status}。']
        for identifier, text, negatives, unknowns in entries:
            ordinal = len(paragraphs)
            start = sum(len(p)+1 for p in paragraphs)
            paragraphs.append(text)
            facts.append(dict(id=identifier, file=name+'.docx', route=route, revision=revision,
                              paragraph=ordinal, start=start, end=start+len(text), fact=text,
                              explicit_negatives=negatives, preserve_unknown=unknowns))
        documents.append(dict(file=name+'.docx', route=route, revision=revision, status=status, paragraphs=paragraphs))

    add('01_儒路线_人物与学宫', '儒', [
        ('R01','儒路线伏生，人物本体编号为伏生，版本为儒伏生；他任青庐学宫讲席。', ['不是法路线的断狱官'], []),
        ('R02','儒伏生在青庐借出一条青色围巾给学生安禾，后来在同一日傍晚收回。', ['不能改成从未借出','不能改成仍未归还'], []),
        ('R03','收回围巾时，儒伏生与安禾都在青庐东廊，时间是傍晚，天色尚未全黑。', ['不是深夜'], []),
        ('R04','医师林川的唯一人物编号是儒医林川，出生于桥北村；他与法路线库吏林川不是同一人。', ['不是盐岭村出生'], []),
        ('R05','青庐学宫的典籍馆钥匙由医师林川保管。林川没有把钥匙交给伏生。', ['伏生未取得钥匙'], []),
        ('R06','儒路线采用春分会讲制度：议题先公开七日，再在春分当天讨论。', [], []),
        ('R07','儒伏生不识法路线的赤铜令，也没有见过道路线的海图。', ['不认识赤铜令','未见海图'], []),
        ('R08','安禾在傍晚为儒伏生倒了一杯水，记录到倒水为止，没有记录任何人饮水。', ['不能断言伏生已经喝水'], ['是否饮水未知']),
        ('R09','儒路线河名是清沅，渡口称东津；从东津到学宫需先经过竹桥。', [], []),
        ('R10','儒路线没有设定伏生的出生年，也没有设定他是否有亲生兄弟。', [], ['出生年未知','亲生兄弟未知']),
    ])
    add('02_法路线_人物与官署', '法', [
        ('F01','法路线伏生的本体编号仍为伏生，版本为法伏生；他任黑石官署断狱官。', ['不是儒路线讲席'], []),
        ('F02','法伏生持有赤铜令，编号赤令十九；此令只在法路线官署有效。', ['不能转给儒伏生使用'], []),
        ('F03','库吏林川的唯一人物编号是法库林川，出生于盐岭村；他与儒医林川同名但不是同一人。', ['不是桥北村出生'], []),
        ('F04','法库林川保管的是乙库账册，没有保管青庐典籍馆的钥匙。', ['不是典籍馆钥匙保管者'], []),
        ('F05','法路线勘验制度要求先封存原件，再由两名见证人登记编号。', [], []),
        ('F06','法伏生在午后将官署正门上锁；侧门和后窗当时的状态没有记载。', ['门上锁不等于人物被困'], ['是否能从别处离开未知']),
        ('F07','正门上锁后的记录中没有外来访客，没有人从外面开门。', ['不能编造开门者'], []),
        ('F08','法路线河名是玄渠，渡口称北埠；北埠到官署须经过石堤。', [], []),
        ('F09','法伏生从未获得儒伏生借还围巾的个人记忆。', ['分身记忆不自动同步'], []),
        ('F10','黑石官署的掌印人是陶宁，法伏生只能提出封印申请，不能自行改换掌印人。', [], []),
    ])
    add('03_道路线_人物与航行', '道', [
        ('D01','道路线伏生的本体编号为伏生，版本为道伏生；他在听潮岛担任潮汐观测者。', ['不是法伏生的修订'], []),
        ('D02','道伏生保存一张白帆海图，图上仅标明听潮岛与南汀，不含青庐学宫。', ['不能推得学宫坐标'], []),
        ('D03','道伏生在辰时把海图交给舟师闻舟，在午时由闻舟原样交还。', ['午时以后海图不在闻舟手中'], []),
        ('D04','听潮岛的潮尺零点设在旧礁最高刻痕，测量员每次必须注明潮次。', [], []),
        ('D05','道路线的议事制度是潮会，只有退潮结束后才举行；它不是春分会讲。', [], []),
        ('D06','道伏生没有赤铜令，也未参加黑石官署的断狱。', ['不能使用法伏生经历'], []),
        ('D07','闻舟在海图背面留下三点墨记，三点只代表复核完成，不代表发现新岛。', ['不能新增岛屿'], []),
        ('D08','道路线没有设定听潮岛与儒路线东津之间的航程。', [], ['跨路线航程未知']),
        ('D09','道路线从听潮岛出航先到南汀，随后是否继续向东航行尚未决定。', ['东行不是既成事实'], ['后续航线待定']),
        ('D10','三位伏生是同一本体的平行版本，彼此没有自动共享知识、物品或记忆的能力。', ['不能合并三个版本'], []),
    ])
    add('04_法路线_税制旧稿', '法', [('V01','旧稿规定仓税为三成。本文件仅保留修订历史，已被作者正式修订第二版取代。', ['三成不是当前有效税率'], [])], '已被取代', '1')
    add('05_法路线_作者修订', '法', [('V02','作者正式修订第二版：仓税从三成改为一成；只替换旧稿仓税条款，其余法路线资料不变。', ['不能保留三成为现行规则'], [])], revision='2')
    add('06_儒路线_门禁甲本', '儒', [('C01','门禁甲本写：青庐北门在辰时开放。甲乙两本权威相同，作者尚未裁定冲突。', [], ['北门准确开放时间待裁定'])])
    add('07_儒路线_门禁乙本', '儒', [('C02','门禁乙本写：青庐北门在巳时开放。甲乙两本权威相同，作者尚未裁定冲突。', [], ['北门准确开放时间待裁定'])])
    add('08_道路线_天梯草案', '道', [('P01','天梯工程只是拟议草案，尚未建成，也未获批准；预算和施工日期都没有确定。', ['不能说已经建成','不能说已经批准'], ['预算未知','施工日期未知'])], '草稿')

    # Distinct synthetic local records, not a repeated paragraph used to inflate volume.
    entries = [NOTICE, '长章节：三路线独立记录汇编。每条编号仅属于本测试作品，不形成跨路线记忆。']
    routes = [('儒','青庐','藏书','讲席'),('法','黑石','案卷','见证人'),('道','听潮','潮尺','观测者')]
    places = ['东廊','南庭','西室','北院','沿河石阶','旧塔侧室','临水平台','竹林边棚']
    actions = ['登记','复核','封存','移交','暂缓','校对','返还','分置']
    fixed = sum(sum(len(p) for p in d['paragraphs']) for d in documents)
    # Account for the exact duplicate retained as a separate source path.
    fixed += sum(map(len, documents[0]['paragraphs']))
    n = 0
    while fixed + sum(map(len, entries)) < target:
        route,city,item,job = routes[n % 3]
        place,action = places[(n//3)%8],actions[(n//24)%8]
        k = n+1
        templates = [
            f'第{k:05d}号记录仅属{route}路线：{city}{place}的{job}登记{item}{k}册，复核序号为{k*7+3}。其中{(k%9)+1}册留在原柜，其余另架保存；记录不涉及其他路线人物。',
            f'{route}路线第{k:05d}次盘查发生在{city}{place}。{item}编号{k*11}与编号{k*11+1}分置两格，{job}只完成{action}，尚未确认第二格的封签来源。不得把待查来源写成已核实。',
            f'{city}{place}留存{route}路线札记{k:05d}：本次{action}的材料有{(k%17)+2}份，最早编号{k*13}，最晚编号{k*13+(k%17)+1}。后续修订须注明札记编号，不能用这一条覆盖邻近札记。',
            f'编号{k:05d}的{route}路线记录保存{city}{place}的一次交接：{job}收到{item}副本{k*3}，原件仍留原保管处。收到副本不代表拥有原件，也不能推出已经通读全部内容。',
            f'{route}路线在{city}{place}的第{k:05d}页对照表记载：旧标记{k*5}改为新标记{k*5+2}，只改这一页标记；{item}正文与相邻页不改。修改记录不是新人物或新世界。',
            f'第{k:05d}份{route}路线待办列于{city}{place}：{job}准备{action}{(k%7)+1}件{item}，但本条是准备记录，并未记录完成。具体完成时间与后续接收人仍然未知。',
            f'{city}{place}第{k:05d}张{route}路线凭单把{item}分为甲乙两批：甲批{(k%13)+1}件已清点，乙批{(k%19)+2}件尚未清点。总量可相加，处理状态不可混为全部完成。',
            f'{route}路线第{k:05d}次{action}只核对{city}{place}的纸面编号{k*17}，没有观察物品实况。{job}注明“编号相符不等于实物无误”，保持纸面证据和实体判断的区别。',
        ]
        entries.append(templates[(n//3)%8])
        n += 1
    documents.append(dict(file='09_三路线_超长章节.docx',route='三路线分列',revision='1',status='正式',paragraphs=entries))
    manifest = []
    for document in documents:
        path=sources/document['file']
        docx(path, document['paragraphs'])
        manifest.append({k:v for k,v in document.items() if k!='paragraphs'} | dict(characters=sum(map(len,document['paragraphs'])), paragraphs=len(document['paragraphs']), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    duplicate=sources/'10_儒路线_完全重复副本.docx'
    duplicate.write_bytes((sources/documents[0]['file']).read_bytes())
    manifest.append(dict(file=duplicate.name, duplicate_of=documents[0]['file'], characters=manifest[0]['characters'], sha256=manifest[0]['sha256']))
    docx(sources/'11_扫描附录_未解析.docx', [], image_only=True)
    manifest.append(dict(file='11_扫描附录_未解析.docx',characters=0,expected_status='OCR_REQUIRED',sha256=hashlib.sha256((sources/'11_扫描附录_未解析.docx').read_bytes()).hexdigest()))
    queries = [
        ('Q01','两位林川分别是谁、出生在哪里、各自保管什么？',['R04','R05','F03','F04']),
        ('Q02','儒伏生与法伏生能否使用对方的令牌、围巾经历和知识？',['R02','R07','F02','F09','D10']),
        ('Q03','当前仓税是多少，为什么旧资料仍写三成？',['V01','V02']),
        ('Q04','青庐北门到底几点开放？',['C01','C02']),
        ('Q05','三路线的议事或勘验制度分别有哪些关键步骤？',['R06','F05','D05']),
        ('Q06','围巾和海图分别怎样交接，最后在哪里？',['R02','R03','D03']),
        ('Q07','法伏生锁门后是否被困，谁从外面来开门？',['F06','F07']),
        ('Q08','青庐倒水事件能否证明伏生喝过水，当时是什么时候？',['R03','R08']),
        ('Q09','能否从海图推算听潮岛到东津的航程？',['D02','D08']),
        ('Q10','天梯工程已经批准或建成了吗，预算与后续航线是什么？',['P01','D09']),
        ('Q11','儒伏生出生于哪年，兄弟是谁？',['R10']),
    ]
    write_json(root/'source-manifest.json', dict(notice=NOTICE,target_characters=target,files=manifest, input_characters=sum(x['characters'] for x in manifest), unique_record_paragraphs=n, long_chapter_characters=sum(map(len,entries))))
    write_json(gold/'answers.json',dict(facts=facts,queries=[dict(id=i,question=q,fact_ids=f) for i,q,f in queries],offset_unit='Unicode characters in newline-joined generated source paragraphs; paragraph ordinal is zero-based'))
    write_json(root/'questions.json',[dict(id=i,question=q) for i,q,_ in queries])
    write_json(gold/'source-paragraphs.json',{d['file']:d['paragraphs'] for d in documents})
    print(json.dumps(dict(directory=str(root),characters=sum(x['characters'] for x in manifest),facts=len(facts),queries=len(queries)),ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('output',type=Path)
    parser.add_argument('--sizes',nargs='+',type=int,default=[18000,100000,1000000])
    args=parser.parse_args()
    for size in args.sizes: create(args.output/str(size),size)
