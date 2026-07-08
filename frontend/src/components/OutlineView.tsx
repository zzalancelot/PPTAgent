import {
  Card,
  Col,
  Collapse,
  Descriptions,
  Divider,
  Flex,
  Row,
  Space,
  Tag,
  Timeline,
  Typography,
} from "antd";
import { AimOutlined, BulbOutlined, PushpinOutlined } from "@ant-design/icons";
import type { OutlineJson, OutlineSlide } from "../api";
import { slideTypeColor, slideTypeLabel } from "../slideType";

const { Title, Paragraph, Text } = Typography;

function BeatColumn({ title, beats }: { title: string; beats: string[] }) {
  return (
    <Col xs={24} md={8}>
      <Text strong>{title}</Text>
      <ul className="beat-list" style={{ marginTop: 6 }}>
        {beats.map((b, i) => (
          <li key={i}>
            <Text type="secondary">{b}</Text>
          </li>
        ))}
      </ul>
    </Col>
  );
}

function SlideItem({ slide }: { slide: OutlineSlide }) {
  return (
    <Card size="small" style={{ marginBottom: 4 }}>
      <Flex justify="space-between" align="start" gap={12} wrap>
        <Space direction="vertical" size={2} style={{ flex: 1, minWidth: 220 }}>
          <Space size={8} wrap>
            <Tag color="default">#{slide.index}</Tag>
            <Tag color={slideTypeColor(slide.slideType)}>{slideTypeLabel(slide.slideType)}</Tag>
            <Text strong>{slide.title}</Text>
          </Space>
          {slide.subtitleHint ? <Text type="secondary">{slide.subtitleHint}</Text> : null}
          <Text type="secondary" style={{ fontSize: 13 }}>
            <AimOutlined /> {slide.intent}
          </Text>
          {slide.bulletHints?.length ? (
            <ul className="beat-list" style={{ marginTop: 4 }}>
              {slide.bulletHints.map((b, i) => (
                <li key={i}>
                  <Text style={{ fontSize: 13 }}>{b}</Text>
                </li>
              ))}
            </ul>
          ) : null}
          {slide.visualHint ? (
            <Text type="secondary" style={{ fontSize: 12 }}>
              <BulbOutlined /> 视觉：{slide.visualHint}
            </Text>
          ) : null}
        </Space>
      </Flex>
    </Card>
  );
}

export default function OutlineView({ outline }: { outline: OutlineJson }) {
  const { meta, storyline, sections, slides, consistency } = outline;

  return (
    <Space direction="vertical" size={16} style={{ width: "100%" }}>
      <Card variant="borderless">
        <Title level={4} style={{ marginTop: 0 }}>
          {meta.topic}
        </Title>
        <Paragraph type="secondary" style={{ marginBottom: 12 }}>
          {meta.oneLiner}
        </Paragraph>
        <Descriptions size="small" column={{ xs: 1, sm: 2, md: 3 }} bordered>
          <Descriptions.Item label="受众">{meta.audience}</Descriptions.Item>
          <Descriptions.Item label="页数">{meta.slideCount}</Descriptions.Item>
          <Descriptions.Item label="语言">{meta.language}</Descriptions.Item>
          <Descriptions.Item label="叙事方式">
            <Tag color="geekblue">{meta.narrativeArc}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="语气">{meta.tone}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Card variant="borderless" title="故事线 Storyline">
        <Space direction="vertical" size={8} style={{ width: "100%" }}>
          <Paragraph style={{ marginBottom: 0 }}>
            <Text strong>Hook：</Text> {storyline.hook}
          </Paragraph>
          <Paragraph style={{ marginBottom: 0 }}>
            <Text strong>Promise：</Text> {storyline.promise}
          </Paragraph>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            <Text strong>听众动机：</Text> {storyline.audienceMotivation}
          </Paragraph>
        </Space>
        <Divider style={{ margin: "14px 0" }} />
        <Row gutter={16}>
          <BeatColumn title="开场 Opening" beats={storyline.openingBeats} />
          <BeatColumn title="主体 Core" beats={storyline.coreBeats} />
          <BeatColumn title="收尾 Closing" beats={storyline.closingBeats} />
        </Row>
      </Card>

      <Card variant="borderless" title="章节 Sections">
        <Timeline
          items={sections.map((s) => ({
            children: (
              <Space direction="vertical" size={0}>
                <Space size={8}>
                  <Text strong>{s.title}</Text>
                  <Tag>
                    {s.slideRange[0]}–{s.slideRange[1]}
                  </Tag>
                </Space>
                <Text type="secondary">{s.purpose}</Text>
              </Space>
            ),
          }))}
        />
      </Card>

      <Card variant="borderless" title={`逐页大纲 · 共 ${slides.length} 页`}>
        <Space direction="vertical" size={6} style={{ width: "100%" }}>
          {slides.map((slide) => (
            <SlideItem key={slide.index} slide={slide} />
          ))}
        </Space>
      </Card>

      <Collapse
        items={[
          {
            key: "consistency",
            label: (
              <Space>
                <PushpinOutlined />
                一致性约束 Consistency
              </Space>
            ),
            children: (
              <Space direction="vertical" size={12} style={{ width: "100%" }}>
                <div>
                  <Text strong>关键术语</Text>
                  <div style={{ marginTop: 6 }}>
                    {consistency.keyTerms.map((t) => (
                      <Tag key={t.term} color="blue" style={{ marginBottom: 6 }}>
                        {t.term}
                      </Tag>
                    ))}
                  </div>
                </div>
                {consistency.forbiddenTerms.length ? (
                  <div>
                    <Text strong>禁用词</Text>
                    <div style={{ marginTop: 6 }}>
                      {consistency.forbiddenTerms.map((t) => (
                        <Tag key={t} color="red" style={{ marginBottom: 6 }}>
                          {t}
                        </Tag>
                      ))}
                    </div>
                  </div>
                ) : null}
                {consistency.preferredPhrases.length ? (
                  <div>
                    <Text strong>偏好表达</Text>
                    <div style={{ marginTop: 6 }}>
                      {consistency.preferredPhrases.map((t) => (
                        <Tag key={t} color="green" style={{ marginBottom: 6 }}>
                          {t}
                        </Tag>
                      ))}
                    </div>
                  </div>
                ) : null}
                <Paragraph type="secondary" style={{ marginBottom: 0 }}>
                  {consistency.differentiationNote}
                </Paragraph>
              </Space>
            ),
          },
        ]}
      />
    </Space>
  );
}
